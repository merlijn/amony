import {durationInMillisToString} from "../../api/Util";
import {imgAlt} from "../../api/Constants";
import React, {CSSProperties, useState} from "react";
import {Fragment, Video} from "../../api/Model";
import {Api} from "../../api/Api";
import {Form} from "react-bootstrap";
import './FragmentPreview.scss';

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

const FragmentPreview = (props: Props) => {

  const durationInSeconds = Math.round((props.fragment.timestamp_end - props.fragment.timestamp_start) / 1000)
  const [showInfoPanel, setShowInfoPanel] = useState(false)

  const [tags, setTags] = useState<Array<string>>([...props.fragment.tags, ""])

  const deleteFragmentFn = () => {

    console.log(`Deleting fragment ${props.vid}:${props.fragment.index}`)
    Api.deleteFragment(props.vid, props.fragment.index).then((result) => {
      props.onDelete && props.onDelete(result as Video)
    })
  }

  const updateTags = () => {
    Api.updateFragmentTags(props.vid, props.fragment.index, tags).then((result) => {
      console.log("Tags added")
    })
  }

  const removeTag = (index: number) => {
    const copyTags = [...tags]
    copyTags.splice(index, 1)
    setTags(copyTags)
  }

  const updateTag = (index: number, value: string) => {
    const copyTags = [...tags]
    copyTags[index] = value
    if (value !== "" && index == tags.length-1)
      copyTags.push("")

    setTags(copyTags)
  }

  const infoPanel =
    <div className={`fragment-info-panel`}>
      <div className="abs-top-left action-icon-small"
           onClick={(e) => { setShowInfoPanel(false); updateTags() } }>
        <img src="/info_black_24dp.svg" />
      </div>
      <div key="tag-list-header" className="tag-list-header">
        Tags:
      </div>

      <div key="tag-list" className="tag-list">
        {
          tags.map((tag, index) => {
            return (
              <div key={`tag-${index}-${tag}`} className="tag-entry">
                <Form.Control
                  className="tag-input" size="sm" type="text" defaultValue={tag}
                  onChange = { (e) => updateTag(index, e.target.value) }
                />
                <img alt={imgAlt}
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
          <img onClick={ (e) => deleteFragmentFn()} src="/cancel_black_24dp.svg" />
        </div>)
      }
      <div className="abs-top-left action-icon-small" >
        <img onClick={ (e) => setShowInfoPanel(true)} alt={imgAlt} src="/info_black_24dp.svg" />
      </div>
    </div>
  );
}

export default FragmentPreview