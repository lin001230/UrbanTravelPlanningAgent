<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { Eye, EyeOff, Leaf, LockKeyhole, ShieldCheck, TrainFront, UserRound, X } from 'lucide-vue-next'
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
    <button v-if="!required" type="button" class="auth-close" @click="emit('close')">
      <X :size="16" />返回助手
    </button>
    <section class="auth-hero">
      <a class="brand" href="#" @click.prevent="!required && emit('close')">
        <span class="brand-mark"><TrainFront :size="24" :stroke-width="1.8" /></span>
        <span class="brand-name">路小行<span>RoutePilot</span></span>
      </a>
      <div class="auth-hero-copy">
        <div class="eyebrow">YOUR EVERYDAY METRO COMPANION</div>
        <h1>登录后，<span>路线跟着你。</span></h1>
        <p>注册一个账号，地铁出行记录就保存在你名下。换一台设备，也能接着上次的对话。</p>
      </div>
      <div class="auth-illustration" aria-hidden="true">
        <svg viewBox="0 0 520 88" fill="none">
          <path d="M16 58H128Q146 58 160 44L172 32Q184 20 202 20H330Q348 20 360 33L370 43Q382 56 400 56H504" stroke="#e6ede9" stroke-width="6" stroke-linecap="round" />
          <path d="M48 58H128Q146 58 160 44L172 32Q184 20 202 20H330Q348 20 360 33L370 43Q382 56 400 56H468" stroke="#8fc5ae" stroke-width="3" stroke-linecap="round" />
          <circle cx="86" cy="58" r="6" fill="white" stroke="#58a387" stroke-width="3" />
          <circle cx="248" cy="20" r="6" fill="white" stroke="#58a387" stroke-width="3" />
          <circle cx="430" cy="56" r="6" fill="white" stroke="#58a387" stroke-width="3" />
          <text x="86" y="80" text-anchor="middle">你的账号</text>
          <text x="248" y="44" text-anchor="middle">出行记录</text>
          <text x="430" y="80" text-anchor="middle">下一站</text>
        </svg>
      </div>
      <ul class="auth-points">
        <li><ShieldCheck :size="16" />会话只属于当前登录的你</li>
        <li><Leaf :size="16" />账号和密码即可，无需手机验证码</li>
      </ul>
    </section>

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
            <small id="auth-password-error" :class="{ 'is-error': errors.password }">{{ errors.password || '请设置 8–64 位密码。' }}</small>
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
            {{ submitLabel }}
          </button>
        </form>

        <p class="auth-swap">
          <template v-if="isRegister">已经有账号了？<button type="button" @click="switchMode('login')">去登录</button></template>
          <template v-else>还没有账号？<button type="button" @click="switchMode('register')">去注册</button></template>
        </p>
      </div>
    </section>
  </div>
</template>
