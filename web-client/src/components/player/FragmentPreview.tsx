import {durationInMillisToString} from "../../api/Util";
import React, {CSSProperties, useState} from "react";
import {Fragment, Video} from "../../api/Model";
import {Api} from "../../api/Api";
import './FragmentPreview.scss';
import ImgWithAlt from "../shared/ImgWithAlt";
import TagEditor from "../shared/TagEditor";
import {Form} from "react-bootstrap";

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
  const [tags, setTags] = useState<Array<string>>(props.fragment.tags)

  const deleteFragmentFn = () => {

    console.log(`Deleting fragment ${props.vid}:${props.fragment.index}`)
    Api.deleteFragment(props.vid, props.fragment.index).then((result) => {
      props.onDelete && props.onDelete(result as Video)
    })
  }

  const updateTags = () => {

    if (tags.length > 0)
      Api.updateFragmentTags(props.vid, props.fragment.index, tags).then((result) => {
        console.log("Tags added")
      })
  }

  const infoPanel =
    <div className={`fragment-info-panel`}>
      <div className="abs-top-right action-icon-small"
           onClick={(e) => { setTags(props.fragment.tags); setShowInfoPanel(false); } }>
        <ImgWithAlt src="/close_black_24dp.svg" />
      </div>
      <div key="tag-list-header" className="meta-panel-title">Tags</div>
      <TagEditor tags={tags} callBack={(updatedTags) => { setTags(updatedTags) } } />

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