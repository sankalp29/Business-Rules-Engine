import { useState, type FormEvent } from "react"
import { Link, useSearchParams } from "react-router-dom"
import { ApiError, api } from "../api"
import { Banner } from "../components/Banner"
import { FieldDraftEditor } from "../components/FieldDraftEditor"
import { PageHead } from "../components/PageHead"
import { emptyField, fieldErrors, fieldToJson, type FieldDraft } from "../drafts"
import { useTypes } from "../hooks"

export function AddFieldPage() {
  const { types, error, reload, loading } = useTypes()
  const [params] = useSearchParams()
  const [typeName, setTypeName] = useState(() => params.get("type") ?? "")
  const [draft, setDraft] = useState<FieldDraft>(emptyField())
  const [problems, setProblems] = useState<string[]>([])
  const [notice, setNotice] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const selected = (types ?? []).find((type) => type.name === typeName) ?? null
  const otherTypes = (types ?? []).map((type) => type.name).filter((name) => name !== typeName)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const nextProblems = [...(typeName ? [] : ["Choose a type."]), ...fieldErrors(draft, "New field")]
    setProblems(nextProblems)
    setNotice(null)
    if (nextProblems.length > 0) return

    setPending(true)
    try {
      const updated = await api.addField(typeName, fieldToJson(draft))
      setNotice(`Added ${draft.name.trim()} to ${updated.name}.`)
      setDraft(emptyField())
      await reload()
    } catch (caught) {
      const failure = caught instanceof ApiError ? caught : new ApiError("Could not add the field.")
      setProblems([failure.message, ...failure.details])
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="stack page">
      <Link className="back" to="/fields">
        All fields
      </Link>
      <PageHead
        eyebrow="Field"
        title="New field"
        lede="Add one field to a type that is already saved. Rules saved earlier keep the type they were compiled with."
      />
      {error ? <Banner tone="error" title={error.message} details={error.details} /> : null}
      {loading ? <p className="note">Loading types…</p> : null}
      {!loading && !error && (types ?? []).length === 0 ? (
        <section className="sheet stack">
          <h2>No types yet</h2>
          <p>Save a type before adding a field to it.</p>
          <Link className="primary link-button" to="/types/new">
            New type
          </Link>
        </section>
      ) : null}
      {(types ?? []).length > 0 ? (
        <div className="split">
          <form className="sheet stack" onSubmit={(event) => void submit(event)}>
            {notice ? <Banner tone="ok" title={notice} /> : null}
            {problems.length > 0 ? <Banner tone="error" title="The field was not added." details={problems} /> : null}
            <label className="field" htmlFor="field-type">
              <span className="label-text">Type</span>
              <select
                id="field-type"
                className="control"
                value={typeName}
                onChange={(event) => {
                  setTypeName(event.target.value)
                  setNotice(null)
                }}
              >
                <option value="">Choose a type</option>
                {(types ?? []).map((type) => (
                  <option key={type.name} value={type.name}>
                    {type.name}
                  </option>
                ))}
              </select>
            </label>
            <FieldDraftEditor
              draft={draft}
              types={otherTypes}
              canRemove={false}
              onChange={setDraft}
              onRemove={() => undefined}
            />
            <div className="actions">
              <button type="submit" className="primary" disabled={pending || !typeName}>
                {pending ? "Adding…" : "Add field"}
              </button>
            </div>
          </form>
          <aside className="sheet stack">
            <h2>{selected ? selected.name : "Type"}</h2>
            {selected ? (
              <>
                <p className="quiet">
                  {selected.fields?.length ?? 0} {selected.fields?.length === 1 ? "field" : "fields"}
                </p>
                <Link to={`/types/${encodeURIComponent(selected.name)}`}>Open {selected.name}</Link>
              </>
            ) : (
              <p className="note">Choose a type to see its fields.</p>
            )}
          </aside>
        </div>
      ) : null}
    </div>
  )
}
