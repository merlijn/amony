import {durationInMillisToString} from "../../api/Util";
import React, {CSSProperties, useState} from "react";
import './FragmentPreview.scss';
import ImgWithAlt from "../common/ImgWithAlt";
import TagEditor from "../common/TagEditor";
import {ClipDto, ResourceDto} from "../../api/generated";

interface Props {
  style: CSSProperties,
  className: string,
  mediaId: string,
  fragment: ClipDto,
  index: number,
  showDeleteButton: boolean,
  showDuration?: boolean,
  onDelete?: (vid: ResourceDto) => any,
  onClick?: () => any,
}

const FragmentPreview = (props: Props) => {

  const durationInSeconds = Math.round((props.fragment.end - props.fragment.start) / 1000)
  const [showMetaPanel, setShowMetaPanel] = useState(false)
  const [tags, setTags] = useState<Array<string>>(props.fragment.tags || [])

  const deleteFragmentFn = () => {

    // console.log(`Deleting fragment ${props.mediaId}:${props.fragment.index}`)
    // Api.deleteFragment(props.mediaId, props.fragment.index).then((result) => {
    //   props.onDelete && props.onDelete(result as Resource)
    // })
  }

  const saveTags = () => {

    // Api.updateFragmentTags(props.mediaId, props.index, tags).then((result) => {
    //   setTags(tags)
    //   console.log("Tags saved")
    // })
  }

  const metaPanel =
    <div className={`fragment-info-panel`}>
      <div className="abs-bottom-right">
        <ImgWithAlt 
          className="action-icon-small" 
          src="/icons/task.svg" 
          title="save" 
          onClick={(e) => { saveTags(); setShowMetaPanel(false); } } />
      </div>
      <div key="tag-list-header" className="meta-panel-title">Tags</div>
      <TagEditor tags = { tags } showAddButton = { true } showDeleteButton = { true } callBack = { (updatedTags) => { setTags(updatedTags) } } />
    </div>

  return(
    <div style={ props.style } className={ `${props.className} fragment-info-panel` } key={`fragment-${props.mediaId}-${props.fragment.start}`} >
      { showMetaPanel && metaPanel }
      <video muted
             onMouseEnter={(e) => e.currentTarget.play() }
             onMouseLeave={(e) => e.currentTarget.pause() }
             onClick={(e) => {  props.onClick && props.onClick() } }>
        <source src={props.fragment.urls[0]} type="video/mp4"/>
      </video>
      <div className="abs-bottom-left duration-overlay">{`${durationInSeconds}s @ ${durationInMillisToString(props.fragment.end)}`}</div>
      {
        props.showDeleteButton &&
          <div className="delete-fragment-icon">
            <ImgWithAlt onClick={ (e) => deleteFragmentFn()} src="/icons/cancel.svg" />
          </div>
      }
      <div className="tags-button" >
        <ImgWithAlt onClick={ (e) => setShowMetaPanel(true)} src="/icons/tag.svg" />
      </div>
    </div>
  );
}

export default FragmentPreview
