import axios from 'axios'
import { http, writeStoredToken } from './http'

/**
 * 响应沿用现有信封：{ code, message, data }，code === 200 视为成功。
 *
 * POST /user/register  { username, password, nickname }
 * POST /user/login     { username, password } → data: { token, user }
 *
 * 后端暂无退出接口。退出只清除本机令牌；令牌过期由请求拦截器处理。
 */
export const AUTH_PATHS = {
  register: '/user/register',
  login: '/user/login',
}

const USER_STORAGE_KEY = 'routepilot.user'
const memory = { user: null }

function unwrap(result) {
  if (result?.code !== 200) {
    throw new Error(result?.message || '请求未成功，请稍后重试。')
  }
  return result.data
}

function readStorage(key) {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function writeStorage(key, value) {
  try {
    if (value == null) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch {
    // 与令牌一样，存储失败时退回到内存。
  }
}

export function readStoredUser() {
  if (memory.user) return memory.user
  const raw = readStorage(USER_STORAGE_KEY)
  if (!raw) return null
  try {
    const user = JSON.parse(raw)
    if (!user || (!user.username && user.id == null)) return null
    memory.user = user
    return user
  } catch {
    return null
  }
}

export function clearSession() {
  memory.user = null
  writeStoredToken('')
  writeStorage(USER_STORAGE_KEY, null)
}

function persistSession(payload) {
  const token = payload?.token || payload?.accessToken || ''
  const source = payload?.user || payload || {}
  const user = {
    id: source.id ?? null,
    username: source.username || '',
    nickname: source.nickname || source.username || '',
  }
  if (!user.username && user.id == null) {
    throw new Error('登录响应缺少用户信息。')
  }
  memory.user = user
  writeStoredToken(token)
  writeStorage(USER_STORAGE_KEY, JSON.stringify(user))
  return { user, authenticated: true }
}

export async function registerAccount({ username, password, nickname }) {
  const { data } = await http.post(AUTH_PATHS.register, {
    username,
    password,
    nickname: nickname || username,
  })
  const payload = unwrap(data)
  const token = payload?.token || payload?.accessToken
  if (token || payload?.user) return persistSession(payload)
  return {
    authenticated: false,
    user: {
      id: payload?.id ?? null,
      username: payload?.username || username,
      nickname: payload?.nickname || nickname || username,
    },
  }
}

export async function loginAccount({ username, password }) {
  const { data } = await http.post(AUTH_PATHS.login, { username, password })
  return persistSession(unwrap(data)).user
}

export function logoutAccount() {
  clearSession()
}

export function getAuthError(error) {
  if (axios.isAxiosError(error)) {
    const message = error.response?.data?.message
    if (typeof message === 'string' && message) return message
    if (!error.response) return '暂时无法连接账号服务，请确认后端已启动。'
    if (error.response.status === 401 || error.response.status === 403) return '账号或密码不正确。'
    if (error.response.status >= 500) return '账号服务暂时不可用，请稍后重试。'
    return `请求未成功（${error.response.status}），请稍后重试。`
  }
  return error.message || '操作未完成，请稍后重试。'
}
