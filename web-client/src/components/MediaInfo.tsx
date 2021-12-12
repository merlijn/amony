import { useState } from "react";
import { VideoMeta } from "../api/Model";
import ImgWithAlt from "./shared/ImgWithAlt";
import TagEditor from "./shared/TagEditor";
import './MediaInfo.scss';
import { AiOutlineSave } from "react-icons/ai";

const MediaInfo = (props: {meta: VideoMeta, onClose: (meta: VideoMeta) => any }) => {

  const [meta, setMeta] = useState(props.meta)

  return(
      <div className="info-panel">
        <div className="info-panel-title">Title</div>
        <input className="title-input" type="text" defaultValue={meta.title}/>
        <div className="info-panel-title">Comment</div>
        <textarea className="comment-input" placeholder="comment">{meta.comment}</textarea>
        <div className="abs-bottom-right">
          <AiOutlineSave className="info-save-button" title="save" onClick={(e) => { props.onClose(meta) } } />
        </div>
        <div className="info-panel-title">Tags</div>
        <TagEditor 
          tags     = { meta.tags } 
          callBack = { (updatedTags) => { setMeta({...meta, tags: updatedTags }) } } 
        />
      </div>
  );
}

export default MediaInfo;