import {durationInMillisToString} from "../../api/Util";
import React, {CSSProperties, useState} from "react";
import {Fragment, Video} from "../../api/Model";
import {Api} from "../../api/Api";
import {Form} from "react-bootstrap";
import './FragmentPreview.scss';
import ImgWithAlt from "../shared/ImgWithAlt";

interface Props {
  style: CSSProperties,
  className: string,
  vid: string,
  fragment: Fragment,
  showDeleteButton: boolean,
  showDuration?: boolean,
  onDelete?: (vid: Video) => any,
  onClick?: () => any,
}

type Tag = {
  value: string,
  id: string
}

const FragmentPreview = (props: Props) => {

  const durationInSeconds = Math.round((props.fragment.timestamp_end - props.fragment.timestamp_start) / 1000)
  const [showInfoPanel, setShowInfoPanel] = useState(false)

  const newTag = (v: string) => { return { value: v, id: Math.random().toString(16) } }
  const initialTags = () => {
    return [...props.fragment.tags, ""].map ((v) => { return newTag(v) } )
  }

  const [tags, setTags] = useState<Array<Tag>>(initialTags())

  const deleteFragmentFn = () => {

    console.log(`Deleting fragment ${props.vid}:${props.fragment.index}`)
    Api.deleteFragment(props.vid, props.fragment.index).then((result) => {
      props.onDelete && props.onDelete(result as Video)
    })
  }

  const updateTags = () => {

    const tagValues = tags
      .map((v) => { return v.value })
      .filter((v) => { return v !== "" })

    if (tagValues.length > 0)
      Api.updateFragmentTags(props.vid, props.fragment.index, tagValues).then((result) => {
        console.log("Tags added")
      })
  }

  const removeTag = (index: number) => {
    if (tags.length > 1) {
      const copyTags = [...tags]
      copyTags.splice(index, 1)
      setTags(copyTags)
    }
  }

  const updateTag = (index: number, value: string) => {
    const copyTags = [...tags]
    copyTags[index] = { value: value, id: tags[index].id }
    if (value !== "" && index === tags.length-1)
      copyTags.push(newTag(""))
    setTags(copyTags)
  }

  const infoPanel =
    <div className={`fragment-info-panel`}>
      <div className="abs-top-left action-icon-small"
           onClick={(e) => { setShowInfoPanel(false); updateTags() } }>
        <ImgWithAlt src="/tag_black_24dp.svg" />
      </div>
      <div className="abs-top-right action-icon-small"
           onClick={(e) => { setTags(initialTags()); setShowInfoPanel(false); } }>
        <ImgWithAlt src="/close_black_24dp.svg" />
      </div>
      <div key="tag-list-header" className="tag-list-header">Tags</div>
      <div key="tag-list" className="tag-list">
        {
          tags.map((tag, index) => {
            return (
              <div key={`tag-${tag.id}`} className="tag-entry">
                <Form.Control
                  className="tag-input" size="sm" type="text" defaultValue={tag.value}
                  onChange = { (e) => updateTag(index, e.target.value) }
                />
                <ImgWithAlt
                     className="action-icon-medium tag-delete-button"
                     onClick = { (e) => removeTag(index) }
                     src="/cancel_black_24dp.svg" />
              </div>);
          })
        }
      </div>

    </div>

  return(
    <div style={ props.style } className={ `${props.className} fragment-info-panel` } key={`fragment-${props.vid}-${props.fragment.timestamp_start}`} >
      { showInfoPanel && infoPanel }
      <video muted
             onMouseEnter={(e) => e.currentTarget.play() }
             onMouseLeave={(e) => e.currentTarget.pause() }
             onClick={(e) => {  props.onClick && props.onClick() } }>
        <source src={props.fragment.uri} type="video/mp4"/>
      </video>
      <div className="abs-bottom-left duration-overlay">{`${durationInSeconds}s @ ${durationInMillisToString(props.fragment.timestamp_start)}`}</div>
      {
        props.showDeleteButton &&
        (<div className="delete-fragment-icon">
          <ImgWithAlt onClick={ (e) => deleteFragmentFn()} src="/cancel_black_24dp.svg" />
        </div>)
      }
      <div className="abs-top-left action-icon-small" >
        <ImgWithAlt onClick={ (e) => setShowInfoPanel(true)} src="/tag_black_24dp.svg" />
      </div>
    </div>
  );
}

export default FragmentPreview