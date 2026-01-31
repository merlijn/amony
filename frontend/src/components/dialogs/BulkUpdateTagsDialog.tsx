import React, {useEffect, useMemo, useRef, useState} from "react"
import {BulkTagsUpdateDto, modifyResourceTagsBulk, ResourceDto} from "../../api/generated"
import Modal from "../common/Modal"
import "./BulkUpdateTagsDialog.scss"
import Dialog from "../common/Dialog";

type BulkUpdateTagsProps = {
  selectedResources: ResourceDto[]
  visible: boolean
  onUpdate: (result: { tagsAdded: string[]; tagsRemoved: string[] }) => void
  onHide: () => void
}

type TagState = "all" | "none" | "partial"

const BulkUpdateTagsDialog = ({selectedResources, visible, onUpdate, onHide}: BulkUpdateTagsProps) => {
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState<string | undefined>(undefined)
  const [tagsToAdd, setTagsToAdd] = useState<Set<string>>(() => new Set())
  const [tagsToRemove, setTagsToRemove] = useState<Set<string>>(() => new Set())
  const [newTag, setNewTag] = useState("")

  useEffect(() => {
    if (visible) {
      setError(undefined)
      setTagsToAdd(new Set())
      setTagsToRemove(new Set())
      setNewTag("")
    } else {
      setIsSubmitting(false)
    }
  }, [visible, selectedResources])

  const tagUsage = useMemo(() => {
    const counts = new Map<string, number>()
    selectedResources.forEach(resource => {
      resource.tags.forEach(tag => {
        counts.set(tag, (counts.get(tag) ?? 0) + 1)
      })
    })
    return { counts, total: selectedResources.length }
  }, [selectedResources])

  const ensureTagChecked = (tag: string) => {
    setTagsToRemove(prev => {
      const next = new Set(prev)
      next.delete(tag)
      return next
    })
    setTagsToAdd(prev => {
      const next = new Set(prev)
      next.add(tag)
      return next
    })
  }

  const ensureTagUnchecked = (tag: string) => {
    setTagsToAdd(prev => {
      const next = new Set(prev)
      next.delete(tag)
      return next
    })
    setTagsToRemove(prev => {
      const next = new Set(prev)
      next.add(tag)
      return next
    })
  }

  const computeTagState = (tag: string): TagState => {
    if (selectedResources.length === 0) return "none"

    const total = tagUsage.total
    const originalCount = tagUsage.counts.get(tag) ?? 0
    let finalCount = originalCount

    if (tagsToAdd.has(tag)) {
      finalCount = total
    }
    if (tagsToRemove.has(tag)) {
      finalCount = 0
    }

    if (finalCount === total) return "all"
    if (finalCount === 0) return "none"
    return "partial"
  }

  const toggleTag = (tag: string) => {
    setError(undefined)
    const state = computeTagState(tag)
    if (state === "all") {
      ensureTagUnchecked(tag)
    } else {
      ensureTagChecked(tag)
    }
  }

  const allTags = useMemo(() => {
    const tags = new Set<string>()
    for (const tag of tagUsage.counts.keys()) {
      tags.add(tag)
    }
    tagsToAdd.forEach(tag => tags.add(tag))
    tagsToRemove.forEach(tag => tags.add(tag))
    return Array.from(tags).sort((a, b) => a.localeCompare(b))
  }, [tagUsage, tagsToAdd, tagsToRemove])

  const submitNewTag = () => {
    const tag = newTag.trim()
    if (!tag) return
    ensureTagChecked(tag)
    setNewTag("")
  }

  const handleBulkUpdate = () => {
    if (selectedResources.length === 0) {
      setError("Select at least one resource to update.")
      return
    }

    const add = Array.from(tagsToAdd).map(tag => tag.trim()).filter(tag => tag.length > 0)
    const remove = Array.from(tagsToRemove).map(tag => tag.trim()).filter(tag => tag.length > 0)

    if (add.length === 0 && remove.length === 0) {
      setError("Choose at least one tag to add or remove.")
      return
    }

    const payload: BulkTagsUpdateDto = {
      ids: selectedResources.map(resource => resource.resourceId),
      tagsToAdd: add,
      tagsToRemove: remove
    }

    setIsSubmitting(true)
    setError(undefined)

    modifyResourceTagsBulk(selectedResources[0].bucketId, payload) // TODO Using the bucketId of the first resource is not proper
      .then(() => {
        onUpdate({ tagsAdded: add, tagsRemoved: remove })
      })
      .catch(() => {
        setError("Failed to update tags. Please try again.")
      })
      .finally(() => {
        setIsSubmitting(false)
      })
  }

  const TagRow = ({tag}: { tag: string }) => {
    const state = computeTagState(tag)
    const checkboxRef = useRef<HTMLInputElement>(null)

    useEffect(() => {
      if (checkboxRef.current) {
        checkboxRef.current.indeterminate = state === "partial"
      }
    }, [state])

    return (
      <label className="bulk-tag-item">
        <input
          ref={checkboxRef}
          type="checkbox"
          checked={state === "all"}
          onChange={() => toggleTag(tag)}
        />
        <span className="bulk-tag-label">{tag}</span>
        <span className="bulk-tag-indicator">{state}</span>
      </label>
    )
  }

  return (
    <Modal visible = { visible } onHide = { onHide }>
      <Dialog>
        <div className="bulk-tag-modal">
          <h2>Update tags</h2>
          <p>{`Updating ${selectedResources.length} resource${selectedResources.length === 1 ? "" : "s"}.`}</p>

          <div className="bulk-tag-section">
            <div className="bulk-tag-list">
              {allTags.length === 0 && <span className="bulk-tag-empty">No tags yet.</span>}
              {allTags.map(tag => (
                <TagRow key={tag} tag={tag} />
              ))}
            </div>

            <div className="bulk-tag-new">
              <input
                className="bulk-tag-input"
                type="text"
                placeholder="Add tag"
                value={newTag}
                onChange={event => setNewTag(event.target.value)}
                onKeyDown={event => {
                  if (event.key === "Enter") {
                    event.preventDefault()
                    submitNewTag()
                  }
                }}
              />
              <button
                type="button"
                className="bulk-tag-button secondary"
                onClick={submitNewTag}
                disabled={!newTag.trim()}
              >
                Add
              </button>
            </div>
          </div>

          {error && <div className="bulk-tag-error">{error}</div>}

          <div className="bulk-tag-actions">
            <button className="bulk-tag-button" type="button" onClick={onHide} disabled={isSubmitting}>
              Cancel
            </button>
            <button
              className="bulk-tag-button primary"
              type="button"
              onClick={handleBulkUpdate}
              disabled={isSubmitting}
            >
              {isSubmitting ? "Saving..." : "Apply"}
            </button>
          </div>
        </div>
      </Dialog>
    </Modal>
  )
}

export default BulkUpdateTagsDialog
