<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import {
  ArrowDown, ArrowRight, ArrowUp, Check, Clock3, Copy,
  Footprints, Leaf, MapPin, Menu, MessageCircle, PanelLeftClose,
  Plus, RefreshCw, Route, Search, ShieldCheck, Sparkles, TrainFront,
  Trash2, X,
} from 'lucide-vue-next'
import MessageContent from './components/MessageContent.vue'
import { getChatError, sendChatStream } from './api/chat'

const STORAGE_KEY = 'routepilot.conversations.v1'
const storageWarning = ref('')
const conversations = ref(readConversations())
const activeId = ref(conversations.value[0]?.id ?? null)
const input = ref('')
const search = ref('')
const preference = ref('智能推荐')
const preferences = [
  { label: '智能推荐', icon: Sparkles },
  { label: '时间优先', icon: Clock3 },
  { label: '少换乘', icon: Route },
  { label: '少步行', icon: Footprints },
]
const examples = [
  { icon: TrainFront, tag: '日常通勤', title: '从深大到深圳北站', text: '在深圳，从深大地铁站到深圳北站怎么坐地铁？', color: 'green' },
  { icon: Route, tag: '轻松换乘', title: '去机场，想少换乘', text: '在深圳，从福田站去宝安机场，想尽量少换乘，怎么坐地铁？', color: 'blue' },
  { icon: MapPin, tag: '周末出游', title: '坐地铁去海边走走', text: '在深圳，从后海站到大梅沙，怎么坐地铁？请包含出站后的步行指引。', color: 'orange' },
]
const sidebarOpen = ref(false)
const pending = ref(false)
const pendingConversationId = ref(null)
const textarea = ref(null)
const scrollArea = ref(null)
const showScrollButton = ref(false)
const copiedId = ref(null)
const toast = ref('')
let toastTimer
let copyTimer
let streamController

const activeConversation = computed(() => conversations.value.find((item) => item.id === activeId.value))
const messages = computed(() => activeConversation.value?.messages ?? [])
const visibleConversations = computed(() => conversations.value.filter((item) => item.title.toLowerCase().includes(search.value.toLowerCase())))
const isCurrentPending = computed(() => pending.value && pendingConversationId.value === activeId.value)
const canSend = computed(() => input.value.trim().length > 0 && !pending.value)

function readConversations() {
  try {
    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')
    if (!Array.isArray(stored)) return []
    return stored.filter((item) => item && typeof item.id === 'string' && typeof item.title === 'string' && Array.isArray(item.messages))
      .slice(0, 30).map((item) => ({ ...item, messages: item.messages
        .filter((message) => message && typeof message.id === 'string' && typeof message.content === 'string' && ['user', 'assistant'].includes(message.role))
        .map((message) => ({
          ...message,
          steps: Array.isArray(message.steps) ? message.steps.filter((step) => step && typeof step.content === 'string') : [],
          ...(message.status === 'streaming' ? {
            status: 'error', error: true, errorText: '上次规划因页面关闭而中断，已保留收到的步骤。可重新尝试。',
          } : {}),
        })),
      }))
  } catch {
    storageWarning.value = '浏览器暂时无法读取历史记录，本次对话仍可正常使用。'
    return []
  }
}

watch(conversations, (value) => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(value.slice(0, 30)))
  } catch {
    storageWarning.value = '本地存储空间不足或不可用，刷新后可能无法保留本次对话。'
  }
}, { deep: true })

watch(input, async () => {
  await nextTick()
  if (textarea.value) {
    textarea.value.style.height = 'auto'
    textarea.value.style.height = `${Math.min(textarea.value.scrollHeight, 160)}px`
  }
})

function notify(message) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = '' }, 3000)
}

async function scrollToBottom(smooth = false) {
  await nextTick()
  scrollArea.value?.scrollTo({ top: scrollArea.value.scrollHeight, behavior: smooth ? 'smooth' : 'instant' })
  showScrollButton.value = false
}

function handleScroll() {
  const el = scrollArea.value
  if (el) showScrollButton.value = el.scrollHeight - el.scrollTop - el.clientHeight > 160
}

function newConversation() {
  activeId.value = null
  input.value = ''
  sidebarOpen.value = false
  nextTick(() => textarea.value?.focus())
}

function selectConversation(id) {
  activeId.value = id
  input.value = ''
  sidebarOpen.value = false
  scrollToBottom()
}

function deleteConversation(id) {
  if (pending.value && pendingConversationId.value === id) return
  conversations.value = conversations.value.filter((item) => item.id !== id)
  if (activeId.value === id) activeId.value = null
}

function useExample(example) {
  input.value = example.text
  nextTick(() => textarea.value?.focus())
}

async function submit() {
  if (!canSend.value) return
  const text = input.value.trim()
  const request = preference.value === '智能推荐' ? text : `${text}\n出行偏好：${preference.value}。`
  if (!activeConversation.value) {
    const conversation = { id: crypto.randomUUID(), title: text.slice(0, 26), messages: [] }
    conversations.value.unshift(conversation)
    activeId.value = conversation.id
  }
  const conversation = activeConversation.value
  conversation.messages.push({ id: crypto.randomUUID(), role: 'user', content: request })
  input.value = ''
  await requestReply(conversation, request)
}

async function requestReply(conversation, request, failedMessage) {
  if (pending.value) return
  pending.value = true
  pendingConversationId.value = conversation.id
  if (failedMessage) conversation.messages.splice(conversation.messages.indexOf(failedMessage), 1)
  const reply = reactive({
    id: crypto.randomUUID(), role: 'assistant', content: '', steps: [],
    status: 'streaming', request, error: false, errorText: '',
  })
  conversation.messages.push(reply)
  const controller = new AbortController()
  streamController = controller
  scrollToBottom(true)
  try {
    await sendChatStream(request, {
      signal: controller.signal,
      onEvent(event) {
        if (event.type === 'final') {
          reply.content = event.content
          reply.status = 'done'
        } else {
          reply.steps.push({ number: event.number ?? reply.steps.length + 1, content: event.content })
        }
        if (activeId.value === conversation.id && !showScrollButton.value) scrollToBottom()
      },
    })
  } catch (error) {
    reply.status = 'error'
    reply.error = true
    reply.errorText = getChatError(error)
  } finally {
    streamController = null
    pending.value = false
    pendingConversationId.value = null
    if (activeId.value === conversation.id) {
      if (!showScrollButton.value) scrollToBottom(true)
      nextTick(() => textarea.value?.focus())
    }
  }
}

function retry(message) {
  if (!pending.value && activeConversation.value && typeof message.request === 'string') {
    requestReply(activeConversation.value, message.request, message)
  }
}

function handleKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault()
    submit()
  }
}

async function copyMessage(message) {
  try {
    const content = message.content || message.steps?.map((step) => `第 ${step.number} 步\n${step.content}`).join('\n\n')
    await navigator.clipboard.writeText(content)
    copiedId.value = message.id
    clearTimeout(copyTimer)
    copyTimer = setTimeout(() => { copiedId.value = null }, 2000)
  } catch {
    notify('复制未成功，请选中文字手动复制。')
  }
}

onBeforeUnmount(() => {
  streamController?.abort(new Error('页面已关闭，流式接收已停止。'))
  clearTimeout(copyTimer)
  clearTimeout(toastTimer)
})
</script>

<template>
  <div class="app-shell" @keydown.esc="sidebarOpen = false">
    <button v-if="sidebarOpen" class="sidebar-backdrop" aria-label="关闭侧栏" @click="sidebarOpen = false"></button>
    <aside class="sidebar" :class="{ 'is-open': sidebarOpen }">
      <a class="brand" href="#" aria-label="路小行首页" @click.prevent="newConversation">
        <span class="brand-mark"><TrainFront :size="24" :stroke-width="1.8" /></span>
        <span class="brand-name">路小行<span>RoutePilot</span></span>
      </a>
      <button class="mobile-close icon-button" aria-label="关闭侧栏" @click="sidebarOpen = false"><PanelLeftClose :size="20" /></button>
      <button class="new-chat" @click="newConversation"><Plus :size="18" />开启新对话<span>NEW</span></button>
      <label class="history-search"><Search :size="16" /><input v-model="search" aria-label="搜索历史对话" placeholder="搜索历史对话" /></label>
      <div class="history-heading"><span>最近对话</span><span>{{ conversations.length.toString().padStart(2, '0') }}</span></div>
      <nav class="history-list" aria-label="历史对话">
        <div v-for="conversation in visibleConversations" :key="conversation.id" class="history-item" :class="{ active: activeId === conversation.id }">
          <button class="history-select" :aria-current="activeId === conversation.id ? 'page' : undefined" @click="selectConversation(conversation.id)"><MessageCircle :size="16" /><span>{{ conversation.title }}</span></button>
          <button class="delete-chat icon-button" :disabled="pending && pendingConversationId === conversation.id" :aria-label="`删除对话：${conversation.title}`" @click="deleteConversation(conversation.id)"><Trash2 :size="14" /></button>
        </div>
        <div v-if="!visibleConversations.length" class="history-empty">
          <MessageCircle :size="24" :stroke-width="1.4" />
          <p>{{ search ? '没有找到相关对话' : '每一次出发，都从这里开始' }}</p>
          <span>{{ search ? '换个关键词试试' : '你的路线规划会保存在这里' }}</span>
        </div>
      </nav>
      <div class="sidebar-bottom">
        <div class="green-note"><span class="leaf-icon"><Leaf :size="19" /></span><div><strong>让城市，近一点</strong><p>搭乘地铁，轻松低碳出行</p></div></div>
        <div class="local-note"><ShieldCheck :size="14" /><span>对话记录仅保存在当前浏览器</span></div>
      </div>
    </aside>

    <main class="main-panel">
      <header class="topbar">
        <div class="topbar-title"><button class="mobile-menu icon-button" aria-label="打开侧栏" :aria-expanded="sidebarOpen" @click="sidebarOpen = true"><Menu :size="21" /></button><span>地铁出行助手</span><span class="ai-badge">AI</span></div>
        <div class="topbar-caption"><span class="status-dot"></span>每一程，都有好路线</div>
      </header>

      <div v-if="storageWarning" class="storage-warning" role="status">{{ storageWarning }}<button class="icon-button" aria-label="关闭提示" @click="storageWarning = ''"><X :size="16" /></button></div>

      <div ref="scrollArea" class="conversation-scroll" @scroll="handleScroll">
        <section v-if="!messages.length" class="welcome">
          <div class="welcome-symbol"><TrainFront :size="39" :stroke-width="1.55" /><span><Sparkles :size="14" /></span></div>
          <div class="eyebrow">YOUR EVERYDAY METRO COMPANION</div>
          <h1>下一站，<span>想去哪里？</span></h1>
          <p class="welcome-description">我是路小行，你的地铁出行搭子。<br />告诉我起点和终点，把路线规划交给我。</p>

          <div class="journey-illustration" aria-hidden="true">
            <svg viewBox="0 0 640 95" fill="none">
              <path d="M18 62H149Q169 62 185 46L198 33Q210 21 229 21H410Q429 21 442 35L452 45Q465 59 485 59H622" stroke="#e6ede9" stroke-width="6" stroke-linecap="round" />
              <path d="M64 62H149Q169 62 185 46L198 33Q210 21 229 21H410Q429 21 442 35L452 45Q465 59 485 59H570" stroke="#8fc5ae" stroke-width="3" stroke-linecap="round" />
              <path d="M263 83L288 58Q301 45 322 45H510Q530 45 545 29L560 13" stroke="#e9c59b" stroke-width="3" stroke-linecap="round" />
              <circle cx="98" cy="62" r="6" fill="white" stroke="#58a387" stroke-width="3" /><circle cx="251" cy="21" r="5" fill="white" stroke="#8fc5ae" stroke-width="3" /><circle cx="400" cy="21" r="6" fill="white" stroke="#58a387" stroke-width="3" /><circle cx="534" cy="59" r="6" fill="white" stroke="#58a387" stroke-width="3" />
              <rect x="311" y="7" width="31" height="28" rx="9" fill="#24876c" /><path d="M321 14h11v11h-11zM324 28l-2 2m7-2 2 2M321 20h11" stroke="white" stroke-width="1.4" stroke-linecap="round" />
              <text x="98" y="87" text-anchor="middle">你的起点</text><text x="400" y="47" text-anchor="middle">轻松换乘</text><text x="534" y="85" text-anchor="middle">想去的地方</text>
            </svg>
          </div>

          <div class="examples-heading"><span>从一个出行灵感开始</span><span>试着这样问 <ArrowDown :size="13" /></span></div>
          <div class="example-grid">
            <button v-for="example in examples" :key="example.tag" class="example-card" @click="useExample(example)">
              <span class="example-icon" :class="example.color"><component :is="example.icon" :size="20" :stroke-width="1.7" /></span>
              <span class="example-tag">{{ example.tag }}</span><strong>{{ example.title }}</strong><span class="example-action">帮我规划一下 <ArrowRight :size="15" /></span>
            </button>
          </div>
        </section>

        <section v-else class="message-list" aria-label="聊天记录" role="log" aria-live="polite" aria-relevant="additions text">
          <div class="conversation-start"><span>一段好旅程，从聊聊开始</span></div>
          <article v-for="message in messages" :key="message.id" class="message" :class="message.role">
            <template v-if="message.role === 'assistant'">
              <div class="assistant-avatar"><TrainFront :size="20" /></div>
              <div class="assistant-body"><div class="assistant-name">路小行<span>出行助手</span></div>
                <details v-if="message.steps?.length" class="planning-steps" :open="message.status !== 'done'">
                  <summary><Route :size="14" />规划过程 · {{ message.steps.length }} 步<span>{{ message.status === 'streaming' ? '进行中' : message.error ? '已中断' : '已完成' }}</span></summary>
                  <div v-for="(step, index) in message.steps" :key="index" class="planning-step"><div class="step-label">第 {{ step.number }} 步</div><MessageContent :content="step.content || '本步未返回文字内容。'" /></div>
                </details>
                <div v-if="message.status === 'streaming'" class="stream-progress"><div class="thinking" role="status"><span class="thinking-dots"><i></i><i></i><i></i></span>{{ message.steps.length ? '正在继续规划路线…' : '正在为你规划路线…' }}</div><p class="thinking-caption">{{ message.steps.length ? '每完成一步，结果会实时显示在上方' : '正在等待第一步结果，规划可能需要一点时间' }}</p></div>
                <div v-if="message.error" class="error-message"><p>{{ message.errorText || message.content }}</p><button class="retry-button" :disabled="pending" @click="retry(message)"><RefreshCw :size="14" />重新尝试</button></div>
                <template v-else-if="message.content"><div v-if="message.steps?.length" class="final-result-label"><Check :size="15" />最终结果</div><MessageContent :content="message.content" /></template>
                <button v-if="message.content && !message.error" class="copy-button" @click="copyMessage(message)"><Check v-if="copiedId === message.id" :size="14" /><Copy v-else :size="14" />{{ copiedId === message.id ? '已复制' : '复制' }}</button>
              </div>
            </template>
            <div v-else class="user-bubble">{{ message.content }}</div>
          </article>
        </section>
      </div>

      <div class="composer-region">
        <button v-if="showScrollButton" class="scroll-bottom icon-button" aria-label="滚动到最新消息" @click="scrollToBottom(true)"><ArrowDown :size="19" /></button>
        <form class="composer" @submit.prevent="submit">
          <label class="sr-only" for="message-input">描述你的地铁出行需求</label>
          <textarea id="message-input" ref="textarea" v-model="input" rows="2" maxlength="2000" placeholder="从哪里出发，想去哪里？也可以说说你的出行偏好…" @keydown="handleKeydown"></textarea>
          <div class="composer-toolbar">
            <div class="preferences" aria-label="出行偏好"><button v-for="item in preferences" :key="item.label" type="button" :class="{ selected: preference === item.label }" :aria-pressed="preference === item.label" @click="preference = item.label"><component :is="item.icon" :size="14" /><span>{{ item.label }}</span></button></div>
            <div class="send-controls"><span v-if="input.length > 1800" class="char-count">{{ input.length }}/2000</span><button type="submit" class="send-button" :disabled="!canSend" :aria-label="pending ? '正在等待回复' : '发送消息'" :title="pending ? '请等待当前规划完成' : '发送消息'"><span v-if="pending" class="send-spinner"></span><ArrowUp v-else :size="21" /></button></div>
          </div>
        </form>
        <div class="composer-footer"><span>路线由 AI 生成，请以实际运营信息为准</span><span class="keyboard-hint">Enter 发送 <span>·</span> Shift + Enter 换行</span></div>
        <div v-if="pending && !isCurrentPending" class="other-pending" role="status">另一段对话正在规划中，完成后即可发送新消息。</div>
      </div>
      <Transition name="toast"><div v-if="toast" class="toast-message" role="status">{{ toast }}</div></Transition>
    </main>
  </div>
</template>
