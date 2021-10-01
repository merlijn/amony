import {durationInMillisToString} from "../../api/Util";
import React, {CSSProperties, useState} from "react";
import {Fragment, Video} from "../../api/Model";
import {Api} from "../../api/Api";
import './FragmentPreview.scss';
import ImgWithAlt from "../shared/ImgWithAlt";
import TagEditor from "../shared/TagEditor";

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
  const [showMetaPanel, setShowMetaPanel] = useState(false)
  const [tags, setTags] = useState<Array<string>>(props.fragment.tags)

  const deleteFragmentFn = () => {

    console.log(`Deleting fragment ${props.vid}:${props.fragment.index}`)
    Api.deleteFragment(props.vid, props.fragment.index).then((result) => {
      props.onDelete && props.onDelete(result as Video)
    })
  }

  const saveTags = () => {

    Api.updateFragmentTags(props.vid, props.fragment.index, tags).then((result) => {
      setTags(tags)
      console.log("Tags saved")
    })
  }

  const metaPanel =
    <div className={`fragment-info-panel`}>
      <div className="abs-bottom-right">
        <ImgWithAlt className="action-icon-small" title="save" src="/icons/task.svg" onClick={(e) => { saveTags(); setShowMetaPanel(false); } } />
      </div>
      <div key="tag-list-header" className="meta-panel-title">Tags</div>
      <TagEditor tags={tags} callBack={ (updatedTags) => { setTags(updatedTags) } } />
    </div>

  return(
    <div style={ props.style } className={ `${props.className} fragment-info-panel` } key={`fragment-${props.vid}-${props.fragment.timestamp_start}`} >
      { showMetaPanel && metaPanel }
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
          <ImgWithAlt onClick={ (e) => deleteFragmentFn()} src="/icons/cancel.svg" />
        </div>)
      }
      <div className="abs-top-left action-icon-small" >
        <ImgWithAlt onClick={ (e) => setShowMetaPanel(true)} src="/icons/tag.svg" />
      </div>
    </div>
  );
}

export default FragmentPreview