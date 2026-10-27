<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ArrowRight, ChevronRight, Eye, EyeOff, Leaf, LockKeyhole, ShieldCheck, TrainFront, UserRound, X } from 'lucide-vue-next'
import { getAuthError, loginAccount, registerAccount } from '../api/auth'

defineProps({
  required: { type: Boolean, default: false },
})

const emit = defineEmits(['close', 'success'])

const mode = ref('login')
const showPassword = ref(false)
const submitting = ref(false)
const formError = ref('')
const formNotice = ref('')
const usernameInput = ref(null)
const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
})
const errors = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
})

const isRegister = computed(() => mode.value === 'register')
const submitLabel = computed(() => {
  if (submitting.value) return isRegister.value ? '正在创建账号…' : '正在登录…'
  return isRegister.value ? '创建账号' : '登录'
})

const USERNAME_PATTERN = /^[A-Za-z0-9_]{4,32}$/

function clearErrors() {
  errors.username = ''
  errors.password = ''
  errors.confirmPassword = ''
  errors.nickname = ''
  formError.value = ''
}

function validate() {
  clearErrors()
  const username = form.username.trim()
  if (!username) errors.username = '请填写账号。'
  else if (!USERNAME_PATTERN.test(username)) errors.username = '账号为 4–32 位字母、数字或下划线。'

  if (!form.password) errors.password = '请填写密码。'
  else if (form.password.length < 8 || form.password.length > 64) errors.password = '密码长度为 8–64 位。'

  if (isRegister.value) {
    if (!form.confirmPassword) errors.confirmPassword = '请再次填写密码。'
    else if (form.confirmPassword !== form.password) errors.confirmPassword = '两次填写的密码不一致。'
    if ([...form.nickname.trim()].length > 64) errors.nickname = '昵称最多 64 个字符。'
  }

  return !errors.username && !errors.password && !errors.confirmPassword && !errors.nickname
}

function switchMode(next) {
  if (mode.value === next || submitting.value) return
  mode.value = next
  showPassword.value = false
  form.password = ''
  form.confirmPassword = ''
  formNotice.value = ''
  clearErrors()
  nextTick(() => usernameInput.value?.focus())
}

async function submit() {
  formNotice.value = ''
  if (submitting.value || !validate()) return
  submitting.value = true
  formError.value = ''
  const username = form.username.trim()
  const nickname = form.nickname.trim()
  try {
    if (isRegister.value) {
      const result = await registerAccount({ username, password: form.password, nickname })
      if (result.authenticated) {
        emit('success', result.user)
        return
      }
      form.password = ''
      form.confirmPassword = ''
      formNotice.value = '账号已创建，使用刚才的账号登录即可。'
      mode.value = 'login'
      return
    }
    const user = await loginAccount({ username, password: form.password })
    emit('success', user)
  } catch (error) {
    formError.value = getAuthError(error)
  } finally {
    submitting.value = false
  }
}

onMounted(() => usernameInput.value?.focus())
</script>

<template>
  <div class="auth-page">
    <div class="auth-shell">
    <button v-if="!required" type="button" class="auth-close" @click="emit('close')">
      <X :size="16" />返回助手
    </button>
    <div class="auth-left">
      <a class="brand" href="#" @click.prevent="!required && emit('close')">
        <span class="brand-mark"><TrainFront :size="22" :stroke-width="1.8" /></span>
        <span class="brand-name">路小行<span>RoutePilot</span></span>
      </a>
      <p class="auth-tagline">用智能路线，<br>发现更大的世界</p>
      <div class="auth-hero-copy">
        <div class="eyebrow">YOUR EVERYDAY METRO COMPANION</div>
        <h1>登录后，<span>路线跟着你。</span></h1>
        <p>注册一个账号，地铁出行记录就保存在你名下。<br>换一台设备，也能接着上次的对话。</p>
        <div class="auth-route" aria-hidden="true">
          <span class="auth-route-line"></span>
          <div class="auth-stop"><i></i><span>你的账号</span></div>
          <div class="auth-stop is-current"><i></i><span>出行记录</span></div>
          <div class="auth-stop"><i></i><span>下一站</span></div>
        </div>
      </div>
      <ul class="auth-points">
        <li><span class="auth-point-icon"><ShieldCheck :size="15" /></span>会话只属于当前登录的你</li>
        <li><span class="auth-point-icon"><Leaf :size="15" /></span>账号和密码即可，无需手机验证码</li>
      </ul>
    </div>
    <header class="auth-scene">
      <svg class="auth-scene-art" viewBox="0 0 640 420" aria-hidden="true">
        <circle cx="210" cy="72" r="58" fill="#f4f6e0" opacity=".9" />
        <path d="M80 176c80-42 168-48 250-8 62 30 118 22 230-18v270H40z" fill="#d9eee6" />
        <path d="M40 236c90-70 170-46 248 8 58 40 118 18 180-28 48-36 110-18 172 28v176H40z" fill="#b9dccb" />
        <g fill="#c5d8cc">
          <rect x="318" y="148" width="8" height="48" rx="2" />
          <rect x="330" y="128" width="7" height="68" rx="2" />
          <rect x="341" y="156" width="12" height="40" rx="2" />
          <rect x="356" y="136" width="7" height="60" rx="2" />
          <rect x="366" y="162" width="10" height="34" rx="2" />
        </g>
        <path d="M0 292c92-78 176-38 248 16 52 40 108 18 176-32 62-46 132-22 216 42v102H0z" fill="#8fbfa3" />
        <path d="M390 96c42 50 18 90-22 124-36 30-18 72 26 108 38 32 70 42 90 86" stroke="#f7f5ef" stroke-width="28" fill="none" stroke-linecap="round" />
        <path d="M390 96c42 50 18 90-22 124-36 30-18 72 26 108 38 32 70 42 90 86" stroke="#e4e2d8" stroke-width="18" fill="none" stroke-linecap="round" />
        <path d="M386 122c28 36 12 64-16 88" stroke="#fff" stroke-width="2" fill="none" stroke-linecap="round" stroke-dasharray="6 8" />
        <g transform="translate(418 214) rotate(24)">
          <rect width="42" height="16" rx="4" y="7" fill="#f7f9fb" />
          <path d="M8 7 14 0h16l6 7" fill="#e6eef4" />
          <circle cx="11" cy="24" r="3.6" fill="#66747c" />
          <circle cx="30" cy="24" r="3.6" fill="#66747c" />
        </g>
        <g fill="#3c8b66">
          <circle cx="96" cy="318" r="22" />
          <circle cx="132" cy="336" r="16" />
          <circle cx="178" cy="308" r="18" />
          <circle cx="236" cy="332" r="20" />
        </g>
        <g fill="#6d5140">
          <rect x="93" y="332" width="6" height="18" rx="2" />
          <rect x="175" y="322" width="6" height="16" rx="2" />
        </g>
        <g transform="translate(452 118)">
          <path d="M18 2a14 14 0 0 0-14 14c0 11 14 24 14 24s14-13 14-24A14 14 0 0 0 18 2z" fill="#2f8f6b" />
          <circle cx="18" cy="16" r="5.2" fill="#fff" />
        </g>
      </svg>
    </header>

    <section class="auth-panel">
      <div class="auth-card">
        <div class="auth-switch" role="tablist" aria-label="登录或注册">
          <button type="button" role="tab" :aria-selected="!isRegister" :class="{ selected: !isRegister }" @click="switchMode('login')">登录</button>
          <button type="button" role="tab" :aria-selected="isRegister" :class="{ selected: isRegister }" @click="switchMode('register')">注册</button>
        </div>
        <h2>{{ isRegister ? '创建路小行账号' : '欢迎回来' }}</h2>
        <p class="auth-lead">{{ isRegister ? '起一个账号名，以后就用它登录。' : '用账号和密码继续你的行程。' }}</p>

        <form @submit.prevent="submit">
          <label class="auth-field">
            <span class="auth-label">账号</span>
            <span class="auth-control" :class="{ invalid: errors.username }">
              <UserRound :size="16" />
              <input
                ref="usernameInput"
                v-model="form.username"
                name="username"
                autocomplete="username"
                maxlength="32"
                placeholder="4–32 位字母、数字或下划线"
                :aria-invalid="Boolean(errors.username)"
                aria-describedby="auth-username-error"
              />
            </span>
            <small id="auth-username-error" :class="{ 'is-error': errors.username }">{{ errors.username || '登录时不区分大小写。' }}</small>
          </label>

          <label v-if="isRegister" class="auth-field">
            <span class="auth-label">昵称 <em>选填</em></span>
            <span class="auth-control" :class="{ invalid: errors.nickname }">
              <UserRound :size="16" />
              <input
                v-model="form.nickname"
                name="nickname"
                autocomplete="nickname"
                maxlength="64"
                placeholder="留空时使用账号名"
                :aria-invalid="Boolean(errors.nickname)"
                aria-describedby="auth-nickname-error"
              />
            </span>
            <small id="auth-nickname-error" :class="{ 'is-error': errors.nickname }">{{ errors.nickname || '显示在页面上的名字，可以和别人重复。' }}</small>
          </label>

          <label class="auth-field">
            <span class="auth-label">密码</span>
            <span class="auth-control" :class="{ invalid: errors.password }">
              <LockKeyhole :size="16" />
              <input
                v-model="form.password"
                name="password"
                :type="showPassword ? 'text' : 'password'"
                :autocomplete="isRegister ? 'new-password' : 'current-password'"
                maxlength="64"
                placeholder="8–64 位"
                :aria-invalid="Boolean(errors.password)"
                aria-describedby="auth-password-error"
              />
              <button type="button" class="auth-reveal" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">
                <EyeOff v-if="showPassword" :size="16" />
                <Eye v-else :size="16" />
              </button>
            </span>
            <small id="auth-password-error" :class="{ 'is-error': errors.password }">{{ errors.password || '请输入 8-64 位密码。' }}</small>
          </label>

          <label v-if="isRegister" class="auth-field">
            <span class="auth-label">确认密码</span>
            <span class="auth-control" :class="{ invalid: errors.confirmPassword }">
              <LockKeyhole :size="16" />
              <input
                v-model="form.confirmPassword"
                name="confirmPassword"
                :type="showPassword ? 'text' : 'password'"
                autocomplete="new-password"
                maxlength="64"
                placeholder="再输入一次密码"
                :aria-invalid="Boolean(errors.confirmPassword)"
                aria-describedby="auth-confirm-error"
              />
            </span>
            <small id="auth-confirm-error" :class="{ 'is-error': errors.confirmPassword }">{{ errors.confirmPassword || '需要和上面的密码一致。' }}</small>
          </label>

          <p v-if="formNotice" class="auth-notice" role="status">{{ formNotice }}</p>
          <p v-if="formError" class="auth-error" role="alert">{{ formError }}</p>

          <button type="submit" class="auth-submit" :disabled="submitting">
            <span v-if="submitting" class="send-spinner"></span>
            <span>{{ submitLabel }}</span>
            <ArrowRight v-if="!submitting" :size="16" />
          </button>
        </form>

        <p class="auth-swap">
          <template v-if="isRegister">已经有账号了？<button type="button" @click="switchMode('login')">去登录 <ChevronRight :size="14" /></button></template>
          <template v-else>还没有账号？<button type="button" @click="switchMode('register')">去注册 <ChevronRight :size="14" /></button></template>
        </p>
      </div>
    </section>
    <svg class="auth-foliage" viewBox="0 0 390 72" aria-hidden="true">
      <path d="M0 46c28-18 48-8 70 6 18 12 36 8 54-8 20-18 42-12 60 4 16 14 34 10 52-6 22-20 40-14 58 6 14 16 30 12 46-4v28H0z" fill="#d7efe4" />
      <path d="M250 40c16-14 34-8 48 6 12 12 28 8 42-4 16-14 32-8 50 8v22H240z" fill="#c5e6d6" />
      <ellipse cx="28" cy="58" rx="16" ry="8" fill="#b7ddc8" transform="rotate(-18 28 58)" />
      <ellipse cx="360" cy="54" rx="18" ry="8" fill="#b7ddc8" transform="rotate(16 360 54)" />
    </svg>
    </div>
  </div>
</template>
