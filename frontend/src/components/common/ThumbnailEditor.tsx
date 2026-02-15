import React, {useEffect, useRef, useState} from "react";
import {MediaPlayerInstance} from "@vidstack/react";
import {getResourceById, ResourceDto, updateThumbnailTimestamp} from "../../api/generated";
import './ThumbnailEditor.scss';

interface ThumbnailEditorProps {
  resource: ResourceDto;
  player: React.RefObject<MediaPlayerInstance | null>;
  onResourceUpdated: (resource: ResourceDto) => void;
}

const ThumbnailEditor = ({resource, player, onResourceUpdated}: ThumbnailEditorProps) => {

  const [expanded, setExpanded] = useState(false);
  const [saving, setSaving] = useState(false);
  const expandedRef = useRef(false);

  // Keep controls pinned while the thumbnail editor is expanded.
  // Vidstack's own button handlers resume idle tracking, so we listen
  // for the controls-change event and re-pause whenever that happens.
  useEffect(() => {
    expandedRef.current = expanded;
    const el = player.current?.el;
    if (!el) return;

    if (expanded) {
      player.current!.remoteControl.pauseControls();

      const handler = () => {
        if (expandedRef.current) {
          player.current?.remoteControl.pauseControls();
        }
      };
      el.addEventListener('controls-change', handler);
      return () => el.removeEventListener('controls-change', handler);
    } else {
      player.current!.remoteControl.resumeControls();
    }
  }, [expanded, player]);

  const onThumbnailClick = () => {
    if (!expanded) {
      if (player.current) {
        if (resource.thumbnailTimestamp !== undefined) {
          player.current.currentTime = resource.thumbnailTimestamp / 1000;
        }
        player.current.pause();
      }
      setExpanded(true);
    }
    else
      setExpanded(false);
  }

  const onClose = () => {
    setExpanded(false);
  }

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

  const thumbnailClassName = `thumbnail-editor-preview ${expanded ? "thumbnail-editor-preview-expanded" : "thumbnail-editor-preview-collapsed"}`

  return (
    <>
      <div className="thumbnail-editor-controls" style = { !expanded ? { "display" : "none" } : { } as React.CSSProperties }>
        <button className="te-btn" onClick={() => forwards(-1)}>-1s</button>
        <button className="te-btn" onClick={() => forwards(-0.1)}>-.1s</button>
        <button className="te-btn" onClick={() => forwards(-(1 / fps))}>-1f</button>
        <button className="te-btn te-btn-save" onClick={updateThumbnailTS} disabled={saving}>
          {saving ? '...' : 'Set'}
        </button>
        <button className="te-btn" onClick={() => forwards(1 / fps)}>+1f</button>
        <button className="te-btn" onClick={() => forwards(0.1)}>+.1s</button>
        <button className="te-btn" onClick={() => forwards(1)}>+1s</button>
        <button className="te-btn te-btn-close" onClick={onClose}>✕</button>
      </div>
      <div className = "thumbnail-editor-preview-container">
        <div className= { thumbnailClassName } onClick={onThumbnailClick}>
          <img
            src={resource.urls.thumbnailUrl}
            alt="thumbnail"
          />
        </div>
      </div>
    </>
  );
}

export default ThumbnailEditor;
