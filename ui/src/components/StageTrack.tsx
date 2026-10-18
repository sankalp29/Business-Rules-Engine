import { useState } from "react"
import { ApiError, api } from "../api"
import { approvalMarks, isApprovedStage, isEditable, stageText } from "../format"
import type { RuleResponse, RuleVersion } from "../model"
import { Banner } from "./Banner"

export function StageTrack({
  ruleKey,
  version,
  onChanged,
}: {
  ruleKey: string
  version: RuleVersion
  onChanged: (rule: RuleResponse) => void
}) {
  const marks = approvalMarks(version)
  const decisionKey = `${version.version}:${version.stage ?? ""}`
  const [seenKey, setSeenKey] = useState(decisionKey)
  const [comment, setComment] = useState("")
  const [error, setError] = useState<ApiError | null>(null)
  const [pending, setPending] = useState(false)
  if (seenKey !== decisionKey) {
    setSeenKey(decisionKey)
    setComment("")
    setError(null)
  }
  const draft = isEditable(version)
  const approved = isApprovedStage(version)
  const terminal = version.stage?.trim() === "APPROVED"

  async function decide(action: "APPROVE" | "REJECT") {
    const text = comment.trim()
    if (action === "REJECT" && !text) {
      setError(new ApiError("Invalid decision", ["A comment is required when sending a version back to draft."]))
      return
    }
    if (text.length > 2000) {
      setError(new ApiError("Invalid decision", ["Comment must be 2000 characters or fewer."]))
      return
    }
    setPending(true)
    setError(null)
    try {
      await api.decide(ruleKey, version.version, action === "REJECT" ? { action, comment: text } : { action })
      onChanged(await api.getRule(ruleKey))
      setComment("")
    } catch (caught) {
      setError(caught instanceof ApiError ? caught : new ApiError("The decision was not saved."))
    } finally {
      setPending(false)
    }
  }

  return (
    <section className="stage-track" aria-busy={pending}>
      <div className="stage-track-head">
        <h2>Approval</h2>
        <p className="quiet">
          {version.workflowVersion ? `Approval path ${version.workflowVersion}. ` : null}
          {stageText(version)} is the current stage.
        </p>
      </div>
      <ol className="stage-line" aria-label="Approval path">
        {marks.map((mark) => (
          <li
            key={mark.name}
            className={mark.status}
            aria-current={mark.status === "current" ? "step" : undefined}
            aria-label={`${mark.label}, ${mark.status === "complete" ? "done" : mark.status === "current" ? "current" : "not yet"}`}
          >
            <span className="stage-dot" aria-hidden="true" />
            <span className="stage-name">{mark.label}</span>
          </li>
        ))}
      </ol>
      {version.returnedFrom ? (
        <p className="return-note">
          Sent back from {version.returnedFrom.label || "the previous stage"}. {version.returnedFrom.comment}
        </p>
      ) : null}
      {error ? <Banner tone="error" title={error.message} details={error.details} /> : null}
      {terminal ? (
        <p className="note">Approved is the last stage. It cannot move forward or be sent back to draft.</p>
      ) : (
        <div className="stage-actions">
          {approved ? null : (
            <button type="button" className="primary" onClick={() => void decide("APPROVE")} disabled={pending}>
              {pending ? "Saving…" : "Approve"}
            </button>
          )}
          {draft ? null : (
            <div className="stage-return">
              <label className="field" htmlFor={`return-${version.version}`}>
                <span className="label-text">Comment</span>
                <textarea
                  id={`return-${version.version}`}
                  className="control"
                  rows={2}
                  maxLength={2000}
                  value={comment}
                  placeholder="Why this version goes back to draft"
                  onChange={(event) => setComment(event.target.value)}
                />
              </label>
              <button type="button" className="danger" onClick={() => void decide("REJECT")} disabled={pending}>
                Send back to draft
              </button>
            </div>
          )}
        </div>
      )}
    </section>
  )
}
