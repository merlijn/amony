import React, {useContext, useState} from 'react';
import {
  canBrowserPlayType,
  dateMillisToString,
  durationInMillisToString,
  labelForResolution,
  titleFromPath
} from "../api/Util";
import FragmentsPlayer from "./common/FragmentsPlayer";
import './Preview.scss';
import {ErrorBoundary} from "react-error-boundary";
import {SessionContext} from "../api/Constants";
import {ResourceDto} from "../api/generated";
import LazyImage from "./common/LazyImage";
import {FiAlertCircle} from "react-icons/fi";
import {MdDelete} from "react-icons/md";
import DeleteResourceDialog from "./dialogs/DeleteResourceDialog";

export type PreviewProps = {
  resource: ResourceDto,
  options: PreviewOptions,
  onClick: (v: ResourceDto) => any,
  onDelete?: (v: ResourceDto) => void
}

export type PreviewOptions = {
  showPreviewOnHover: boolean,
  showPreviewOnHoverDelay?: number,
  showInfoBar: boolean,
  showDates: boolean,
  showDuration: boolean,
  showResolution: boolean,
}

const Preview = (props: PreviewProps) => {
  const resource = props.resource
  const [isHovering, setIsHovering] = useState(false)
  const [showDeleteDialog, setShowDeleteDialog] = useState(false)

  const durationStr = durationInMillisToString(resource.contentMeta.duration)

  const mediaTitle  = resource.title || titleFromPath(resource.path)
  const isVideo   = resource.contentType.startsWith("video")
  const session = useContext(SessionContext)
  const isMediaTypeSupported = canBrowserPlayType(resource.contentType)

  const titlePanel =
      <div className = "preview-info-bar">
        <span className="media-title" title={mediaTitle}>{mediaTitle}</span>
        { props.options.showDates && <span className="media-date">{dateMillisToString(resource.timeCreated ?? resource.timeAdded)}</span> }
      </div>

  const overlay =
      <div className="preview-overlay">
        { props.options.showResolution && <div className="preview-quality-overlay">{labelForResolution(resource.contentMeta.height)}</div> }
        { (isVideo && props.options.showDuration) && <div className="duration-overlay">{durationStr}</div> }
        { isHovering && session.isAdmin() && props.onDelete && <div className="preview-delete-icon-overlay" onClick={(e) => { e.stopPropagation(); setShowDeleteDialog(true) }}><MdDelete /></div> }
        { !isMediaTypeSupported && <div className="preview-unsupported-overlay"><FiAlertCircle color="#fff" /></div> }
      </div>

  const primaryThumbnail =
      <LazyImage
        loadImage = { () =>
          <img
              src       = { resource.urls.thumbnailUrl } alt="an image"
              onClick   = { () => props.onClick(props.resource) }
              className = { `preview-thumbnail preview-media` }
          />
        }
      />

  const videoPreview =
      <FragmentsPlayer
          key       = { `video-preview-${props.resource.resourceId}` }
          className = { `preview-video preview-media` }
          onClick   = { () => props.onClick(props.resource) }
          fragments = { props.resource.clips } />

  const preview =
    <ErrorBoundary fallback={ <div /> }>
      <div className    = "preview-media-container"
           onMouseEnter = { () => props.options.showPreviewOnHover && setIsHovering(true) }
           onMouseLeave = { () => setIsHovering(false) }>
        { isVideo && isHovering && videoPreview }
        { primaryThumbnail }
        { overlay }
      </div>
    </ErrorBoundary>

  return (
      <div className = "preview-resource">
        { preview }
        { props.options.showInfoBar && titlePanel }
        <DeleteResourceDialog
          resource={props.resource}
          visible={showDeleteDialog}
          onDeleted={(r) => { setShowDeleteDialog(false); props.onDelete?.(r) }}
          onHide={() => setShowDeleteDialog(false)}
        />
      </div>
  )
}

export default Preview
