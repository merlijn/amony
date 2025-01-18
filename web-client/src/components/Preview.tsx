import React, {CSSProperties, useContext, useEffect, useState} from 'react';
import ProgressiveImage from "react-progressive-graceful-image";
import {dateMillisToString, durationInMillisToString, labelForResolution} from "../api/Util";
import FragmentsPlayer from "./common/FragmentsPlayer";
import ImgWithAlt from "./common/ImgWithAlt";
import './Preview.scss';
import {ErrorBoundary} from "react-error-boundary";
import {SessionContext} from "../api/Constants";
import {ResourceDto} from "../api/generated";

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

  const isVideo = resource.contentType.startsWith("video")

  const session = useContext(SessionContext)

  const titlePanel =
      <div className = "preview-info-bar">
        <span className="media-title" title={resource.userMeta.title}>{resource.userMeta.title}</span>
        { props.options.showDates && <span className="media-date">{dateMillisToString(resource.uploadTimestamp)}</span> }
      </div>

  const overlay =
      <div className="preview-overlay">
        { props.options.showResolution && <div className="preview-quality-overlay">{labelForResolution(resource.contentMeta.height)}</div> }
        { (isVideo && props.options.showDuration) && <div className="duration-overlay">{durationStr}</div> }
        { isHovering && session.isAdmin() && <a className="preview-edit-icon-overlay" href={`/editor/${props.resource.bucketId}/${props.resource.resourceId}`}><ImgWithAlt src="/icons/edit.svg" /></a> }
        {/* { <div className="abs-bottom-right"><FiDownload /></div> } */}
      </div>

  const primaryThumbnail =
      <ProgressiveImage src = { resource.urls.thumbnailUrl } placeholder="/image_placeholder.svg">
        { (src: string) =>
            <img
                src       = { src } alt="an image"
                onClick   = { () => props.onClick(props.resource) }
                className = { `preview-thumbnail preview-media` }
            />
        }
      </ProgressiveImage>

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
