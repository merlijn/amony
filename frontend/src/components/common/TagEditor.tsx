import React, {useEffect, useRef, useState} from "react";
import './TagEditor.scss';
import { MdClose } from "react-icons/md";

export type TagEditorProps = {
  tags: Array<string>, 
  callBack: (tags: Array<string>) => void, 
  showAddButton?: boolean
  showDeleteButton?: boolean
}

const TagEditor = (props: TagEditorProps) => {

  const [tags, setTags] = useState(props.tags)
  const newTagRef = useRef<HTMLSpanElement>(null)

  useEffect(() => {
    setTags(props.tags)
  }, [props])

  let newTagActive = false

  const addTag = (blur: boolean) => {
    if (newTagRef.current && newTagActive) {
      const tagValue = newTagRef?.current?.innerText.trim()

      if (tagValue && tagValue !== "") {

        const newTags = [...tags, tagValue]
        setTags(newTags)
        newTagActive = false

        if (blur)
          newTagRef.current.blur()

        props.callBack(newTags)
      }
    }
  }

  return (<div className="tag-container">
    {
      tags.map ( (tag, idx) =>
        <div key={`tag-${tag}`} className="tag">{tag}
          { props.showDeleteButton && <MdClose
            className = "delete-tag"
            onClick={() => {
              const newTags = [...tags.slice(0, idx), ...tags.slice(idx + 1, tags.length) ]
              setTags(newTags)
              props.callBack(newTags)
            }}
          /> }
        </div>
      )
    }
    { props.showAddButton &&
      <div className = "new-tag">
          <span
            ref       = { newTagRef }
            key       = { "new-tag" }
            className = "input"
            role      = "textbox"
            onFocus   = { () => {
                if (newTagRef.current)
                  newTagRef.current.innerText = "";
                newTagActive = true;
              }
            }
            onBlur = { () =>
              {
                addTag(false);
                if (newTagRef.current)
                  newTagRef.current.innerText = "+";

                newTagActive = false
              }
            }
            onKeyPress = { (k) => {
                if (k.key === "Enter") {
                  k.preventDefault()
                  addTag(true)
                }
              }
            }
            contentEditable
            suppressContentEditableWarning={true}
            >+
          </span>
      
      </div>
    }
  </div>)
}

export default TagEditor