<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  ArrowDown, ArrowRight, ArrowUp, Check, Clock3, Copy,
  Footprints, Leaf, LogOut, MapPin, Menu, MessageCircle, PanelLeftClose,
  PencilLine, Plus, RefreshCw, Route, Search, ShieldCheck, Sparkles, TrainFront,
  Trash2, UserRound,
} from 'lucide-vue-next'
import AuthView from './components/AuthView.vue'
import MessageContent from './components/MessageContent.vue'
import { clearSession, logoutAccount, readStoredUser } from './api/auth'
import { isUnauthorizedError, onUnauthorized, readStoredToken } from './api/http'
import { deleteChat, getChatError, getChatMessages, getChats, sendChatStream, updateChatTitle } from './api/chat'

const conversations = ref([])
const activeId = ref(null)
const listLoading = ref(Boolean(readStoredToken()))
const listError = ref('')
const deletingIds = reactive(new Set())
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
const currentUser = ref(readStoredToken() ? readStoredUser() : null)
const showAuth = ref(!readStoredToken())
const signingOut = ref(false)
const deleteTarget = ref(null)
const deleteCancelButton = ref(null)
const renameTarget = ref(null)
const renameTitle = ref('')
const renameInput = ref(null)
const renameBusy = ref(false)
const renameError = ref('')
let toastTimer
let copyTimer
let streamController
let stopAuthWatch
let listVersion = 0
let navigationVersion = 0

const activeConversation = computed(() => conversations.value.find((item) => item.id === activeId.value))
const displayName = computed(() => currentUser.value?.nickname || currentUser.value?.username || '')
const userInitial = computed(() => [...displayName.value][0] || '我')
const showAccountName = computed(() => Boolean(currentUser.value?.nickname) && currentUser.value.nickname !== currentUser.value.username)
const messages = computed(() => activeConversation.value?.messages ?? [])
const visibleConversations = computed(() => conversations.value.filter((item) => item.title.toLowerCase().includes(search.value.toLowerCase())))
const isCurrentPending = computed(() => pending.value && pendingConversationId.value === activeId.value)
const canSend = computed(() => input.value.trim().length > 0 && !pending.value
  && !listLoading.value && !listError.value && !activeConversation.value?.loading
  && !activeConversation.value?.loadError && !deletingIds.has(activeId.value))

async function loadConversations(selectFirst = false) {
  const version = ++listVersion
  const navigation = navigationVersion
  listLoading.value = true
  listError.value = ''
  try {
    const sessions = await getChats()
    if (version !== listVersion) return
    const existing = new Map(conversations.value.map((conversation) => [conversation.id, conversation]))
    const serverIds = new Set(sessions.map((session) => session.id))
    const drafts = conversations.value.filter((conversation) => !serverIds.has(conversation.id)
      && (!conversation.persisted || conversation.id === pendingConversationId.value))
    conversations.value = [...drafts, ...sessions.map((session) => {
      const conversation = existing.get(session.id) || { messages: [], loaded: false }
      Object.assign(conversation, session, { persisted: true })
      return conversation
    })]
    if (activeId.value && !conversations.value.some((conversation) => conversation.id === activeId.value)) {
      activeId.value = null
    }
    if (selectFirst && navigation === navigationVersion && !activeId.value && conversations.value.length) {
      await selectConversation(conversations.value[0].id)
    }
  } catch (error) {
    if (version === listVersion && !isUnauthorizedError(error)) listError.value = getChatError(error)
  } finally {
    if (version === listVersion) listLoading.value = false
  }
}

async function loadMessages(conversation, force = false) {
  if (conversation.id === pendingConversationId.value || conversation.loading
      || (conversation.loaded && !force)) return
  conversation.loading = true
  conversation.loadError = ''
  try {
    const history = await getChatMessages(conversation.id)
    if (!conversations.value.includes(conversation)) return
    conversation.messages = history
    conversation.loaded = true
  } catch (error) {
    if (!isUnauthorizedError(error)) conversation.loadError = getChatError(error)
  } finally {
    conversation.loading = false
  }
}

function enterLogin() {
  clearSession()
  currentUser.value = null
  conversations.value = []
  activeId.value = null
  listError.value = ''
  listLoading.value = false
  sidebarOpen.value = false
  showAuth.value = true
}

onMounted(() => {
  stopAuthWatch = onUnauthorized(enterLogin)
  if (!readStoredToken()) {
    enterLogin()
    return
  }
  loadConversations(true)
})

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
  navigationVersion++
  activeId.value = null
  input.value = ''
  sidebarOpen.value = false
  nextTick(() => textarea.value?.focus())
}

async function selectConversation(id) {
  navigationVersion++
  activeId.value = id
  input.value = ''
  sidebarOpen.value = false
  const conversation = activeConversation.value
  if (conversation) await loadMessages(conversation)
  if (activeId.value === id) scrollToBottom()
}

function openAuth() {
  showAuth.value = true
  sidebarOpen.value = false
}

function handleAuthSuccess(user) {
  currentUser.value = user
  showAuth.value = false
  notify(`欢迎，${user.nickname || user.username}`)
  loadConversations(true)
}

function signOut() {
  if (signingOut.value) return
  signingOut.value = true
  logoutAccount()
  signingOut.value = false
  enterLogin()
  notify('已退出登录')
}

function handleEscape() {
  if (showAuth.value) {
    if (currentUser.value) showAuth.value = false
    return
  }
  if (renameTarget.value) {
    cancelRename()
    return
  }
  if (deleteTarget.value) {
    cancelDelete()
    return
  }
  sidebarOpen.value = false
}

function requestDelete(conversation) {
  if (listLoading.value || (pending.value && pendingConversationId.value === conversation.id) || deletingIds.has(conversation.id)) return
  deleteTarget.value = conversation
  nextTick(() => deleteCancelButton.value?.focus())
}

function cancelDelete() {
  deleteTarget.value = null
}

async function confirmDelete() {
  const target = deleteTarget.value
  deleteTarget.value = null
  if (!target) return
  const id = target.id
  const index = conversations.value.findIndex((item) => item.id === id)
  if (index === -1) return
  deletingIds.add(id)
  try {
    await deleteChat(id)
    conversations.value = conversations.value.filter((conversation) => conversation.id !== id)
    if (activeId.value === id) newConversation()
    notify('对话已删除')
  } catch (error) {
    if (!isUnauthorizedError(error)) notify(`删除对话失败。${getChatError(error)}`)
  } finally {
    deletingIds.delete(id)
  }
}

function requestRename(conversation) {
  if (listLoading.value || deletingIds.has(conversation.id) || !conversation.persisted) return
  renameTarget.value = conversation
  renameTitle.value = conversation.title
  renameError.value = ''
  nextTick(() => { renameInput.value?.focus(); renameInput.value?.select() })
}

function cancelRename() {
  if (!renameBusy.value) renameTarget.value = null
}

async function confirmRename() {
  const conversation = renameTarget.value
  const title = renameTitle.value.trim()
  if (!conversation || renameBusy.value) return
  if (!title || [...title].length > 64) {
    renameError.value = '请输入 1–64 个字符的标题。'
    return
  }
  renameBusy.value = true
  renameError.value = ''
  try {
    await updateChatTitle(conversation.id, title)
    conversation.title = title
    renameTarget.value = null
    notify('标题已更新')
  } catch (error) {
    if (!isUnauthorizedError(error)) renameError.value = getChatError(error)
  } finally {
    renameBusy.value = false
  }
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
    const conversation = { id: crypto.randomUUID(), title: text.slice(0, 26), messages: [], loaded: true, persisted: false }
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
      chatId: conversation.id,
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
    // 最终事件由后端在持久化成功后发送，此时刷新列表不会读到旧会话状态。
    await loadConversations()
  } catch (error) {
    if (isUnauthorizedError(error)) return
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
  if (!pending.value && !activeConversation.value?.loading && !activeConversation.value?.loadError
      && activeConversation.value && typeof message.request === 'string') {
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
  stopAuthWatch?.()
  streamController?.abort(new Error('页面已关闭，流式接收已停止。'))
  clearTimeout(copyTimer)
  clearTimeout(toastTimer)
})
</script>

<template>
  <div class="app-root" @keydown.esc="handleEscape">
  <div class="app-shell" :inert="showAuth || undefined">
    <button v-if="sidebarOpen" class="sidebar-backdrop" aria-label="关闭侧栏" @click="sidebarOpen = false"></button>
    <aside class="sidebar" :class="{ 'is-open': sidebarOpen }">
      <a class="brand" href="#" aria-label="路小行首页" @click.prevent="newConversation">
        <span class="brand-mark"><TrainFront :size="24" :stroke-width="1.8" /></span>
        <span class="brand-name">路小行<span>RoutePilot</span></span>
      </a>
      <button class="mobile-close icon-button" aria-label="关闭侧栏" @click="sidebarOpen = false"><PanelLeftClose :size="20" /></button>
      <button class="new-chat" @click="newConversation"><Plus :size="18" />开启新对话<span>NEW</span></button>
      <label class="history-search"><Search :size="16" /><input v-model="search" aria-label="搜索历史对话" placeholder="搜索历史对话" /></label>
      <div class="history-heading"><span>最近对话 · {{ conversations.length }}</span><button class="icon-button history-refresh" :disabled="listLoading" aria-label="刷新会话列表" @click="loadConversations()"><RefreshCw :size="13" :class="{ spinning: listLoading }" /></button></div>
      <nav class="history-list" aria-label="历史对话">
        <p v-if="listLoading && !conversations.length" class="history-state" role="status">正在加载会话…</p>
        <div v-if="listError" class="history-state history-error" role="alert"><p>{{ listError }}</p><button class="retry-button" @click="loadConversations(true)"><RefreshCw :size="13" />重新加载会话</button></div>
        <div v-for="conversation in visibleConversations" :key="conversation.id" class="history-item" :class="{ active: activeId === conversation.id }">
          <button class="history-select" :disabled="deletingIds.has(conversation.id)" :aria-current="activeId === conversation.id ? 'page' : undefined" :title="conversation.title" @click="selectConversation(conversation.id)"><MessageCircle :size="16" /><span>{{ conversation.title }}</span></button>
          <button class="history-action icon-button" :disabled="listLoading || !conversation.persisted || deletingIds.has(conversation.id)" :aria-label="`重命名对话：${conversation.title}`" @click="requestRename(conversation)"><PencilLine :size="13" /></button>
          <button class="history-action delete-chat icon-button" :disabled="listLoading || deletingIds.has(conversation.id) || (pending && pendingConversationId === conversation.id)" :aria-label="`删除对话：${conversation.title}`" @click="requestDelete(conversation)"><Trash2 :size="14" /></button>
        </div>
        <div v-if="!visibleConversations.length && !listLoading && !listError" class="history-empty">
          <MessageCircle :size="24" :stroke-width="1.4" />
          <p>{{ search ? '没有找到相关对话' : '每一次出发，都从这里开始' }}</p>
          <span>{{ search ? '换个关键词试试' : '你的路线规划会保存在这里' }}</span>
        </div>
      </nav>
      <div class="sidebar-bottom">
        <button v-if="!currentUser" type="button" class="account-chip" @click="openAuth"><UserRound :size="16" /><span>登录 / 注册</span></button>
        <div v-else class="account-chip is-signed-in">
          <span class="account-avatar" aria-hidden="true">{{ userInitial }}</span>
          <span class="account-label"><strong>{{ displayName }}</strong><small v-if="showAccountName">{{ currentUser.username }}</small></span>
          <button type="button" class="account-logout" :disabled="signingOut" @click="signOut"><LogOut :size="14" />{{ signingOut ? '退出中' : '退出' }}</button>
        </div>
        <div class="green-note"><span class="leaf-icon"><Leaf :size="19" /></span><div><strong>让城市，近一点</strong><p>搭乘地铁，轻松低碳出行</p></div></div>
        <div class="local-note"><ShieldCheck :size="14" /><span>对话记录已开启服务端保存</span></div>
      </div>
    </aside>

    <main class="main-panel">
      <header class="topbar">
        <div class="topbar-title"><button class="mobile-menu icon-button" aria-label="打开侧栏" :aria-expanded="sidebarOpen" @click="sidebarOpen = true"><Menu :size="21" /></button><span class="current-title" :title="activeConversation?.title">{{ activeConversation?.title || '地铁出行助手' }}</span><span class="ai-badge">AI</span></div>
        <div class="topbar-caption"><span class="status-dot"></span>每一程，都有好路线</div>
      </header>

      <div ref="scrollArea" class="conversation-scroll" @scroll="handleScroll">
        <section v-if="activeConversation?.loading" class="conversation-state" role="status"><RefreshCw class="spinning" :size="22" /><p>正在加载聊天记录…</p></section>
        <section v-else-if="activeConversation?.loadError" class="conversation-state" role="alert"><p>{{ activeConversation.loadError }}</p><button class="retry-button" @click="loadMessages(activeConversation, true)"><RefreshCw :size="14" />重新加载聊天记录</button></section>
        <section v-else-if="!messages.length" class="welcome">
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

    <Transition name="fade">
      <div v-if="deleteTarget" class="confirm-backdrop" @click.self="cancelDelete">
        <div class="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="confirm-delete-title" aria-describedby="confirm-delete-desc">
          <h2 id="confirm-delete-title">删除这个对话？</h2>
          <p id="confirm-delete-desc">「{{ deleteTarget.title }}」的聊天记录将从服务端删除，且无法恢复。</p>
          <div class="confirm-actions">
            <button ref="deleteCancelButton" type="button" class="confirm-cancel" @click="cancelDelete">取消</button>
            <button type="button" class="confirm-delete-btn" @click="confirmDelete">确认删除</button>
          </div>
        </div>
      </div>
    </Transition>
    <Transition name="fade">
      <div v-if="renameTarget" class="confirm-backdrop" @click.self="cancelRename">
        <form class="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="rename-title" @submit.prevent="confirmRename">
          <h2 id="rename-title">重命名对话</h2>
          <label class="sr-only" for="chat-title-input">会话标题</label>
          <input id="chat-title-input" ref="renameInput" v-model="renameTitle" class="rename-input" :disabled="renameBusy" maxlength="128" autocomplete="off" aria-describedby="rename-hint" />
          <p id="rename-hint">为这段对话起一个名字，最多 64 个字符。</p>
          <p v-if="renameError" class="rename-error" role="alert">{{ renameError }}</p>
          <div class="confirm-actions"><button type="button" class="confirm-cancel" :disabled="renameBusy" @click="cancelRename">取消</button><button type="submit" class="confirm-delete-btn" :disabled="renameBusy || !renameTitle.trim()">{{ renameBusy ? '保存中…' : '保存标题' }}</button></div>
        </form>
      </div>
    </Transition>
  </div>
  <AuthView v-if="showAuth" :required="!currentUser" @close="showAuth = false" @success="handleAuthSuccess" />
  </div>
</template>
