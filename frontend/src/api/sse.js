// SSE frames may span network chunks, and one chunk may contain several frames.
// CR, LF and CRLF are all valid line endings; comments/metadata are ignored.
export function createSseParser(onEvent) {
  let line = ''
  let skipLf = false
  let data = []
  let event = ''

  function finishLine() {
    if (line === '') {
      if (data.length) onEvent({ event: event || 'message', data: data.join('\n') })
      data = []
      event = ''
    } else if (!line.startsWith(':')) {
      const colon = line.indexOf(':')
      const field = colon === -1 ? line : line.slice(0, colon)
      let value = colon === -1 ? '' : line.slice(colon + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'data') data.push(value)
      if (field === 'event') event = value
    }
    line = ''
  }

  return (chunk) => {
    for (const char of chunk) {
      if (skipLf && char === '\n') {
        skipLf = false
        continue
      }
      skipLf = false
      if (char === '\r' || char === '\n') {
        finishLine()
        skipLf = char === '\r'
      } else {
        line += char
      }
    }
  }
}

// Matches the text protocol currently emitted by BaseAgent.runStream.
export function parseAgentEvent({ event, data }) {
  if (event === 'error') throw new Error(data || '路线规划失败，请重试。')
  const final = data.match(/^模型最终的输出为[：:][ \t]*/)
  if (final) return { type: 'final', content: data.slice(final[0].length).trim() }
  const step = data.match(/^第\s*(\d+)\s*步的结果为[：:][ \t]*/)
  if (step) return { type: 'step', number: Number(step[1]), content: data.slice(step[0].length) }
  return { type: 'step', content: data }
}
