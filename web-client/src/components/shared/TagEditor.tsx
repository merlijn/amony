import ImgWithAlt from "./ImgWithAlt";
import React, {useRef, useState} from "react";
import './TagEditor.scss';

const TagEditor = (props: {tags: Array<string>, callBack: (tags: Array<string>) => void }) => {

  const [tags, setTags] = useState(props.tags)
  const newTagRef = useRef<HTMLSpanElement>(null)

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
          <ImgWithAlt
            className="delete-tag"
            src="/icons/close.svg"
            onClick={() => {
              const newTags = [...tags.slice(0, idx), ...tags.slice(idx + 1, tags.length) ]
              setTags(newTags)
              props.callBack(newTags)
            }}
          />
        </div>
      )
    }

    <div className="new-tag">
        <span
          ref={newTagRef}
          key={"new-tag"}
          className="input"
          role="textbox"
          onFocus={ () => {
              if (newTagRef.current)
                newTagRef.current.innerText = "";
              newTagActive = true;
            }
          }
          onBlur={ () =>
            {
              addTag(false);
              if (newTagRef.current)
                newTagRef.current.innerText = "+";

              newTagActive = false
            }
          }
          onKeyPress={ (k) => {
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
  </div>)
}

export default TagEditor