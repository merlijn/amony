import { useEffect, useRef, useState } from "react"
import { FiPlusCircle } from "react-icons/fi"
import TagEditor from "./TagEditor"
import './TagsBar.scss'
import {MdOutlineAddCircleOutline} from "react-icons/md";

type TagsBarProps = {
  tags: Array<string>, 
  onTagsUpdated: (tags: Array<string>) => any,
  showAddTagButton: boolean,
  showDeleteButton: boolean,
  className?: string 
}

const TagsBar = (props: TagsBarProps) => {

  const [showNewTag, setShowNewTag] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (showNewTag)
      inputRef?.current?.focus()
  }, [showNewTag]);

  return(
    <div className = { `${props.className} list-cell list-tags` }>
      <div className = "tags-bar-wrapper">
        <TagEditor 
          key              = "tag-editor" 
          showAddButton    = { false }
          showDeleteButton = { props.showDeleteButton }
          tags             = { props.tags } 
          callBack         = { (newTags) => { props.onTagsUpdated(newTags) } } />
        { (!showNewTag && props.showAddTagButton) && <MdOutlineAddCircleOutline onClick = { (e) => setShowNewTag(true) } className="add-tag-button" /> }
        <span 
          contentEditable
          key        = "new-tag"
          className  = "new-tag"
          ref        = { inputRef } 
          style      = { { visibility: showNewTag ? "visible" : "hidden" } }
          onBlur     = { (e) => { 
            e.currentTarget.innerText = ""
            setShowNewTag(false) } 
          } 
          onKeyPress = { (e) => {
            if (e.key === "Enter") {
              e.preventDefault()
              const newTag = e.currentTarget.innerText
              e.currentTarget.innerText = ""
              props.onTagsUpdated([...props.tags, newTag.trim()])
              setShowNewTag(false)
            }
            if (e.key === "Escape") {
              e.currentTarget.blur();
            }
          }
        } ></span>
      </div>
    </div>);
}

export default TagsBar