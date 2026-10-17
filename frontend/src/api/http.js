import axios from 'axios'

/** 登录令牌。后端若改用 Cookie Session，不返回 token 时这里保持为空。 */
export const TOKEN_STORAGE_KEY = 'routepilot.token'
const USER_STORAGE_KEY = 'routepilot.user'

const memory = { token: '' }
const unauthorizedListeners = new Set()
let unauthorizedNotified = false

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  adapter: 'fetch',
  timeout: 15000,
  withCredentials: true,
  headers: { Accept: 'application/json' },
})

http.interceptors.request.use((config) => {
  const token = readStoredToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (response) => {
    if (isUnauthorizedPayload(response.data) && !isAuthRequest(response.config)) {
      notifyUnauthorized()
    }
    return response
  },
  (error) => {
    if (isUnauthorizedError(error) && !isAuthRequest(error.config)) notifyUnauthorized()
    return Promise.reject(error)
  },
)

export function onUnauthorized(listener) {
  unauthorizedListeners.add(listener)
  return () => unauthorizedListeners.delete(listener)
}

export function isUnauthorizedError(error) {
  if (!axios.isAxiosError(error)) return false
  return error.response?.status === 401 || isUnauthorizedPayload(error.response?.data)
}

export function readStoredToken() {
  if (memory.token) return memory.token
  try {
    return localStorage.getItem(TOKEN_STORAGE_KEY) || ''
  } catch {
    return ''
  }
}

export function writeStoredToken(token) {
  memory.token = token || ''
  if (memory.token) unauthorizedNotified = false
  try {
    if (memory.token) localStorage.setItem(TOKEN_STORAGE_KEY, memory.token)
    else localStorage.removeItem(TOKEN_STORAGE_KEY)
  } catch {
    // 浏览器禁用本地存储时，仅保留当前页面内存中的令牌。
  }
}

function isUnauthorizedPayload(data) {
  return data?.code === 401
}

function isAuthRequest(config) {
  const url = config?.url || ''
  return url.includes('/user/login') || url.includes('/user/register')
}

function notifyUnauthorized() {
  if (unauthorizedNotified) return
  unauthorizedNotified = true
  writeStoredToken('')
  try {
    localStorage.removeItem(USER_STORAGE_KEY)
  } catch {
    // 与令牌一样，存储不可用时只通知页面清内存状态。
  }
  unauthorizedListeners.forEach((listener) => listener())
}
