import { useState } from "react"

export function JsonBlock({ title, value, note }: { title: string; value: unknown; note?: string }) {
  const text = JSON.stringify(value, null, 2)
  return <CodeBlock title={title} text={text} note={note} />
}

export function CodeBlock({ title, text, note }: { title: string; text: string; note?: string }) {
  const [copied, setCopied] = useState(false)

  async function copy() {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1400)
    } catch {
      setCopied(false)
    }
  }

  return (
    <section className="sheet code-sheet">
      <header className="sheet-head">
        <h2>{title}</h2>
        <button type="button" className="ghost" onClick={() => void copy()}>
          {copied ? "Copied" : "Copy"}
        </button>
      </header>
      {note ? <p className="note">{note}</p> : null}
      <pre className="source">{text}</pre>
    </section>
  )
}
