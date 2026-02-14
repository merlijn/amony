import React, {CSSProperties, useContext, useState} from 'react';
import {Link} from 'react-router-dom';
import {
  canBrowserPlayType,
  dateMillisToString,
  durationInMillisToString,
  labelForResolution,
  titleFromPath
} from "../api/Util";
import FragmentsPlayer from "./common/FragmentsPlayer";
import ImgWithAlt from "./common/ImgWithAlt";
import './Preview.scss';
import {ErrorBoundary} from "react-error-boundary";
import {SessionContext} from "../api/Constants";
import {ResourceDto} from "../api/generated";
import LazyImage from "./common/LazyImage";
import {FiAlertCircle} from "react-icons/fi";

export type PreviewProps = {
  resource: ResourceDto,
  style?: CSSProperties,
  className?: string,
  lazyLoad?: boolean,
  options: PreviewOptions,
  onClick: (v: ResourceDto) => any
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
  const [resource, setResource] = useState(props.resource)
  const [isHovering, setIsHovering] = useState(false)

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
        {/*{ isHovering && session.isAdmin() && <Link className="preview-edit-icon-overlay" to={`/editor/${props.resource.bucketId}/${props.resource.resourceId}`}><ImgWithAlt src="/icons/edit.svg" /></Link> }*/}
        { !isMediaTypeSupported && <div className="preview-unsupported-overlay"><FiAlertCircle color="#fff" /></div> }
        {/* { <div className="abs-bottom-right"><FiDownload /></div> } */}
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
      <div className = "preview-media">
        { preview }
        { props.options.showInfoBar && titlePanel }
      </div>
  )
}

export default Preview
