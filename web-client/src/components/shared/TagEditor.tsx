import ImgWithAlt from "./ImgWithAlt";
import React, {useRef, useState} from "react";
import './TagEditor.scss';

const TagEditor = (props: {tags: Array<string>, callBack: (tags: Array<string>) => void }) => {

  const [tags, setTags] = useState(props.tags)
  const [newTag, setNewTag] = useState<boolean>(false)
  const newTagRef = useRef<HTMLSpanElement>(null)

  let newTagActive = false

  const addTag = () => {
    if (newTagRef.current && newTagActive) {
      const tagValue = newTagRef?.current?.innerText.trim()

      console.log(`adding tag ${tagValue}`)

      if (tagValue && tagValue !== "") {
        newTagActive = false
        setTags([...tags, tagValue])
        newTagRef.current.innerText = "+"
        newTagRef.current.blur()
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
            onClick={() => { setTags([...tags.slice(0, idx), ...tags.slice(idx + 1, tags.length) ]) } }
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
          } }
          onBlur={ () => { addTag() } }
          onKeyPress={ (k) => {
              if (k.key === "Enter") {
                k.preventDefault()
                addTag()
              }
            }
          }
          contentEditable
          >{ (newTagActive) ? "" : "+"}
        </span>
    </div>
  </div>)
}

export default TagEditor