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
  showDuration?: boolean,
  showDeleteButton: boolean,
  onDelete?: (vid: Video) => any,
  onClick?: () => any,
}

const FragmentPreview = (props: Props) => {

  const durationInSeconds = Math.round((props.fragment.timestamp_end - props.fragment.timestamp_start) / 1000)
  const [showInfoPanel, setShowInfoPanel] = useState(false)
  const [newTags, setNewTags] = useState<Array<string>>([""])

  const deleteFragmentFn = () => {

    console.log(`Deleting fragment ${props.vid}:${props.fragment.index}`)
    Api.deleteFragment(props.vid, props.fragment.index).then((result) => {
      props.onDelete && props.onDelete(result as Video)
    })
  }

  const addTag = (tag: string) => {

  }

  const infoPanel =
    <div className={`fragment-info-panel`}>
      <div className="abs-top-left action-icon-small" onClick={(e) => { setShowInfoPanel(false) } }>
        <img src="/info_black_24dp.svg" />
      </div>
      <Form.Control className="fragment-comment" as="textarea" rows={2} />
      <div className="tag-list">
        {
          props.fragment.tags.map((tag) => {
            return (
              <div className="tag-entry">
                <Form.Control className="tag-input" size="sm" type="text" defaultValue="" />
                <img alt={imgAlt} className="action-icon-medium tag-delete-button" src="/cancel_black_24dp.svg" />
              </div>);
          })
        }
        {
          newTags.map((tag) => {

            return (
              <div className="tag-entry">
                <Form.Control className="tag-input" size="sm" type="text" defaultValue={tag} />
                <img alt={imgAlt} className="action-icon-medium tag-delete-button" src="/cancel_black_24dp.svg" />
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