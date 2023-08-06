import { useState } from "react";
import { ResourceUserMeta } from "../../api/Model";
import TagEditor from "../common/TagEditor";
import './MediaInfo.scss';
import { FiSave } from "react-icons/fi";
import Dialog from "../common/Dialog";

const MediaInfo = (props: {meta: ResourceUserMeta, onClose: (meta: ResourceUserMeta) => any }) => {

  const [meta, setMeta] = useState(props.meta)

  return(
      <Dialog>
        <div className = "media-info-dialog">
          <div className="header">Title</div>
          <input className="title-input" type="text" defaultValue={meta.title}/>
          <div className="header">Comment</div>
          <textarea className="comment-input" placeholder="comment">{meta.comment}</textarea>
          <div className="header">Tags</div>
          <TagEditor 
            showDeleteButton = { true }
            showAddButton    = { true }
            tags             = { meta.tags } 
            callBack         = { (updatedTags) => { setMeta({...meta, tags: updatedTags }) } } 
          />
          <FiSave className = "info-save-button" title = "save" onClick={(e) => { props.onClose(meta) } } />
        </div>
      </Dialog>
  );
}

export default MediaInfo;