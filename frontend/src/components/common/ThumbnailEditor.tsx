import React, {useState} from "react";
import {MediaPlayerInstance} from "@vidstack/react";
import {getResourceById, ResourceDto, updateThumbnailTimestamp} from "../../api/generated";
import './ThumbnailEditor.css';

interface ThumbnailEditorProps {
  resource: ResourceDto;
  player: React.RefObject<MediaPlayerInstance | null>;
  onResourceUpdated: (resource: ResourceDto) => void;
}

const ThumbnailEditor = ({resource, player, onResourceUpdated}: ThumbnailEditorProps) => {

  const [expanded, setExpanded] = useState(false);
  const [saving, setSaving] = useState(false);

  const forwards = (amount: number) => {
    if (player.current) {
      player.current.pause();
      player.current.currentTime = player.current.currentTime + amount;
    }
  }

  const updateThumbnailTS = () => {
    if (player.current && !saving) {
      setSaving(true);
      updateThumbnailTimestamp(
        resource.bucketId,
        resource.resourceId,
        {timestampInMillis: Math.trunc(player.current.currentTime * 1000)}
      ).then(() => {
        getResourceById(resource.bucketId, resource.resourceId).then(updated => {
          onResourceUpdated(updated);
          setSaving(false);
        });
      }).catch(() => setSaving(false));
    }
  }

  const fps = resource.contentMeta.fps;

  if (expanded) {
    return (
      <div className="thumbnail-editor-expanded">
        <div className="thumbnail-editor-controls">
          <button className="te-btn" onClick={() => forwards(-1)}>-1s</button>
          <button className="te-btn" onClick={() => forwards(-0.1)}>-.1s</button>
          <button className="te-btn" onClick={() => forwards(-(1 / fps))}>-1f</button>
          <button className="te-btn te-btn-save" onClick={updateThumbnailTS} disabled={saving}>
            {saving ? '...' : 'Set'}
          </button>
          <button className="te-btn" onClick={() => forwards(1 / fps)}>+1f</button>
          <button className="te-btn" onClick={() => forwards(0.1)}>+.1s</button>
          <button className="te-btn" onClick={() => forwards(1)}>+1s</button>
          <button className="te-btn te-btn-close" onClick={() => setExpanded(false)}>✕</button>
        </div>
        <div className="thumbnail-editor-preview">
          <img
            src={resource.urls.thumbnailUrl}
            alt="thumbnail"
          />
        </div>
      </div>
    );
  }

  return (
    <div className="thumbnail-editor-collapsed" onClick={() => setExpanded(true)}>
      <img
        src={resource.urls.thumbnailUrl}
        alt="thumbnail"
      />
    </div>
  );
}

export default ThumbnailEditor;
