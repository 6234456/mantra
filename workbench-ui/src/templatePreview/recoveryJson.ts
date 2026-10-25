/** The native parser supplies JSON grammar; this bounded scan additionally rejects duplicate object keys. */
export function parseRecoveryJson(raw: string): unknown {
  const stack: Array<{ object: boolean; key: boolean; keys: Set<string> }> = []
  for (let index = 0; index < raw.length; index++) {
    const character = raw[index]
    if (character === '"') {
      const start = index
      let escaped = false
      for (index++; index < raw.length; index++) {
        if (!escaped && raw[index] === '"') break
        if (!escaped && raw[index] === '\\') escaped = true
        else escaped = false
      }
      if (index >= raw.length) throw new Error('The backup is not valid JSON.')
      const frame = stack.at(-1)
      if (frame?.object && frame.key) {
        const key: unknown = JSON.parse(raw.slice(start, index + 1))
        if (typeof key !== 'string' || frame.keys.has(key)) throw new Error('The backup contains duplicate JSON keys.')
        frame.keys.add(key)
        frame.key = false
      }
    } else if (character === '{' || character === '[') {
      stack.push({ object: character === '{', key: character === '{', keys: new Set() })
      if (stack.length > 16) throw new Error('The backup exceeds the maximum JSON nesting depth of 16.')
    } else if (character === '}' || character === ']') {
      stack.pop()
    } else if (character === ',' && stack.at(-1)?.object) {
      stack.at(-1)!.key = true
    }
  }
  return JSON.parse(raw) as unknown
}
