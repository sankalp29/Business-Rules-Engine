import { ThenBlockEditor, WhenBlockEditor } from "./WhenThenEditors"
import { addFact, emptyThen, removeWhen, replaceWhen, valueFieldOptions, type RuleDraft } from "../drafts"
import type { Leaf, TypeResponse } from "../model"

export function LogicFields({
  draft,
  types,
  leavesByWhen,
  onChange,
}: {
  draft: RuleDraft
  types: TypeResponse[]
  leavesByWhen: Leaf[][]
  onChange: (recipe: (draft: RuleDraft) => RuleDraft) => void
}) {
  const untyped = draft.whens.some((when) => !when.type.trim())
  const unused = types.some((type) => !draft.whens.some((when) => when.type === type.name))
  const canAddWhen = !untyped && unused
  const facts = draft.whens.map((when) => ({ binding: when.binding.trim(), type: when.type.trim() }))

  return (
    <>
      {draft.whens.map((when, index) => (
        <WhenBlockEditor
          key={when.id}
          index={index}
          when={when}
          types={types.filter(
            (type) => type.name === when.type || !draft.whens.some((other) => other.id !== when.id && other.type === type.name),
          )}
          leaves={leavesByWhen[index] ?? []}
          valueFields={valueFieldOptions(
            leavesByWhen[index] ?? [],
            draft.whens.slice(0, index).map((block, blockIndex) => ({
              binding: block.binding,
              type: block.type,
              leaves: leavesByWhen[blockIndex] ?? [],
            })),
          )}
          canRemove={draft.whens.length > 1}
          onChange={(next) => onChange((current) => replaceWhen(current, when.id, next))}
          onRemove={() => onChange((current) => removeWhen(current, when.id))}
        />
      ))}
      <button
        type="button"
        className="ghost"
        disabled={!canAddWhen}
        title={untyped ? "Choose a type first." : unused ? undefined : "Each type can be used once."}
        onClick={() => onChange(addFact)}
      >
        Add when block
      </button>
      {!untyped && !unused ? <p className="note">Each type can be used once.</p> : null}
      {draft.thens.map((block, index) => {
        const whenIndex = draft.whens.findIndex((when) => when.binding.trim() === block.binding.trim())
        return (
          <ThenBlockEditor
            key={block.id}
            index={index}
            block={block}
            facts={facts}
            leaves={whenIndex >= 0 ? (leavesByWhen[whenIndex] ?? []) : []}
            whens={draft.whens}
            leavesByWhen={leavesByWhen}
            canRemove={draft.thens.length > 1}
            onChange={(next) =>
              onChange((current) => ({ ...current, thens: current.thens.map((item) => (item.id === block.id ? next : item)) }))
            }
            onRemove={() => onChange((current) => ({ ...current, thens: current.thens.filter((item) => item.id !== block.id) }))}
          />
        )
      })}
      <button
        type="button"
        className="ghost"
        onClick={() => onChange((current) => ({ ...current, thens: [...current.thens, emptyThen(current.whens[0]?.binding ?? "")] }))}
      >
        Add then block
      </button>
    </>
  )
}
