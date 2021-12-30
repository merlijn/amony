import { useState } from "react";
import { VideoMeta } from "../../api/Model";
import TagEditor from "../common/TagEditor";
import './MediaInfo.scss';
import { FiSave } from "react-icons/fi";
import Dialog from "../common/Dialog";

const MediaInfo = (props: {meta: VideoMeta, onClose: (meta: VideoMeta) => any }) => {

  const [meta, setMeta] = useState(props.meta)

  return(
      <Dialog>
        <div className = "media-info">
          <div className="info-panel-title">Title</div>
          <input className="title-input" type="text" defaultValue={meta.title}/>
          <div className="info-panel-title">Comment</div>
          <textarea className="comment-input" placeholder="comment">{meta.comment}</textarea>
          <div className="abs-bottom-right">
            <FiSave className="info-save-button" title="save" onClick={(e) => { props.onClose(meta) } } />
          </div>
          <div className="info-panel-title">Tags</div>
          <TagEditor 
            showAddButton = { true }
            tags          = { meta.tags } 
            callBack      = { (updatedTags) => { setMeta({...meta, tags: updatedTags }) } } 
          />
        </div>
      </Dialog>
  );
}

export default MediaInfo;