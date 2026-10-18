import { useState, type FormEvent } from "react"
import { Link } from "react-router-dom"
import { ApiError, api } from "../api"
import { Banner } from "../components/Banner"
import { FieldDraftEditor } from "../components/FieldDraftEditor"
import { PageHead } from "../components/PageHead"
import { emptyField, fieldErrors, fieldToJson, type FieldDraft } from "../drafts"
import { useTypes } from "../hooks"

export function CreateTypePage() {
  const { types, error, reload, loading } = useTypes()
  const [name, setName] = useState("")
  const [fields, setFields] = useState<FieldDraft[]>([emptyField()])
  const [problems, setProblems] = useState<string[]>([])
  const [notice, setNotice] = useState<string | null>(null)
  const [savedName, setSavedName] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const savedTypes = (types ?? []).map((type) => type.name)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const nextProblems = [...(name.trim() ? [] : ["Enter a type name."]), ...fields.flatMap((field, index) => fieldErrors(field, `Field ${index + 1}`))]
    if (fields.length === 0) nextProblems.push("Add a field.")
    setProblems(nextProblems)
    setNotice(null)
    if (nextProblems.length > 0) return

    setPending(true)
    try {
      const created = await api.createType({ name: name.trim(), fields: fields.map(fieldToJson) })
      setNotice(`Saved ${created.name}.`)
      setSavedName(created.name)
      setName("")
      setFields([emptyField()])
      await reload()
    } catch (caught) {
      const failure = caught instanceof ApiError ? caught : new ApiError("Could not save the type.")
      setProblems([failure.message, ...failure.details])
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="stack page">
      <Link className="back" to="/types">
        All types
      </Link>
      <PageHead
        eyebrow="Type"
        title="New type"
        lede="Name a fact and the fields a rule can read or write."
      />
      {error ? <Banner tone="error" title={error.message} details={error.details} /> : null}
      {notice ? <Banner tone="ok" title={notice} /> : null}
      {savedName ? (
        <p className="actions">
          <Link to={`/types/${encodeURIComponent(savedName)}`}>Open {savedName}</Link>
          <Link to={`/fields/new?type=${encodeURIComponent(savedName)}`}>Add a field</Link>
        </p>
      ) : null}
      {problems.length > 0 ? <Banner tone="error" title="The type was not saved." details={problems} /> : null}
      <div className="split">
        <form className="sheet stack" onSubmit={(event) => void submit(event)}>
          <label className="field" htmlFor="type-name">
            <span className="label-text">Type name</span>
            <input
              id="type-name"
              className="control"
              value={name}
              autoComplete="off"
              spellCheck={false}
              placeholder="Customer"
              onChange={(event) => setName(event.target.value)}
            />
          </label>
          <p className="note">Use a Java name such as Customer or Address. A type needs at least one field.</p>
          <div className="stack">
            {fields.map((field) => (
              <FieldDraftEditor
                key={field.id}
                draft={field}
                types={savedTypes.filter((type) => type !== name.trim())}
                canRemove={fields.length > 1}
                onChange={(next) => setFields((current) => current.map((item) => (item.id === field.id ? next : item)))}
                onRemove={() => setFields((current) => current.filter((item) => item.id !== field.id))}
              />
            ))}
          </div>
          <div className="actions">
            <button type="button" className="ghost" onClick={() => setFields((current) => [...current, emptyField()])}>
              Add field
            </button>
            <button type="submit" className="primary" disabled={pending}>
              {pending ? "Saving…" : "Save type"}
            </button>
          </div>
        </form>
        <aside className="sheet roster">
          <h2>Saved types</h2>
          {loading ? <p className="note">Loading types…</p> : null}
          {!loading && (types ?? []).length === 0 ? <p className="note">None yet.</p> : null}
          {(types ?? []).map((type) => (
            <Link key={type.name} className="saved-type" to={`/types/${encodeURIComponent(type.name)}`}>
              <span>{type.name}</span>
              {type.description ? <span className="quiet">{type.description}</span> : null}
            </Link>
          ))}
        </aside>
      </div>
    </div>
  )
}
