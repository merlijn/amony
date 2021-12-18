import { useEffect, useState } from "react"
import { FaSort } from "react-icons/fa"
import { FiEdit, FiDownload } from "react-icons/fi"
import { RiDeleteBin6Line } from "react-icons/ri"
import { BsPlayCircle } from "react-icons/bs"
import { GrAddCircle } from "react-icons/gr"
import { BsThreeDotsVertical } from "react-icons/bs"
import ProgressiveImage from "react-progressive-graceful-image"
import { Api } from "../api/Api"
import { MediaSelection, SearchResult, Video, VideoMeta } from "../api/Model"
import { dateMillisToString, formatByteSize } from "../api/Util"
import './ListView.scss'
import Scrollable from "./shared/Scrollable"
import TagEditor from "./shared/TagEditor"
import ImgWithAlt from "./shared/ImgWithAlt"

type ListProps = {
  selection: MediaSelection
  onClick: (v: Video) => any
}

const initialSearchResult: SearchResult = { total: 0, videos: [] }

const ListView = (props: ListProps) => {

  const [searchResult, setSearchResult] = useState(initialSearchResult);
  const [isFetching, setIsFetching] = useState(false)
  const [fetchMore, setFetchMore] = useState(true)

  const fetchData = (previous: Array<Video>) => {

    const offset = previous.length
    const n      = 32

    if (n > 0 && fetchMore) {
      Api.getVideoSelection(n, offset, props.selection).then(response => {

          const result = response as SearchResult
          const videos = [...previous, ...result.videos]

          if (videos.length >= result.total)
            setFetchMore(false)

          setIsFetching(false);
          setSearchResult({...response, videos: videos});
        });
      }
  }

  useEffect(() => {
    setSearchResult(initialSearchResult)
    setIsFetching(true)
    setFetchMore(true)
  }, [props.selection])

  useEffect(() => { if (isFetching && fetchMore) fetchData(searchResult.videos); }, [isFetching]);

  const updateTags = (v: Video, tags: Array<string>) => {

    const meta: VideoMeta = { ...v.meta, tags: tags }
    Api.updateVideoMetaData(v.id, meta)
  }

  return (
      <Scrollable
        className = "list-container"
        fetchContent = { () => { if (!isFetching && fetchMore) setIsFetching(true); fetchData(searchResult.videos) } }
        scrollType = 'page'
      >
      <div key="row-header" className="list-row list-header-row">
        <div className="list-cell"></div>
        <div className="list-cell"></div>
        <div className="list-cell list-header">Title<FaSort className="column-sort-icon" /></div>
        <div className="list-cell list-header">Tags</div>
        <div className="list-cell list-header"><FaSort className="column-sort-icon" /></div>
        <div className="list-cell list-header"><FaSort className="column-sort-icon" /></div>
        <div className="list-cell list-header"><FaSort className="column-sort-icon" /></div>
        <div className="list-cell list-header"><BsThreeDotsVertical className="list-menu-icon" /></div>
      </div>
      <div key="row-spacer" className="list-row row-spacer"></div>
      {
        searchResult.videos.map((v, index) => {
          return(
            <div key={`row-${v.id}`} className="list-row">
              <div key="select" className="list-cell list-select">
                <input type="checkbox" />
              </div>

              <div key="thumbnail" className="list-cell list-thumbnail">
              <ProgressiveImage src={v.thumbnail_url} placeholder="/image_placeholder.svg">
                  { (src: string) => 
                    <img className="list-thumbnail-img" src={src} onClick={() => props.onClick(v) } alt="an image" /> }
              </ProgressiveImage>
              </div>

              <div key="title" className="list-cell list-title">
                { v.meta.title }
                <FiEdit className="edit-title action" />
              </div>
              
              <div key="tags" className="list-cell list-tags">
                <TagEditor showAddButton={true} tags={v.meta.tags} callBack = { (tags) => { updateTags(v, tags) } } />
                {/* <GrAddCircle className="edit-title action" /> */}
              </div>

              <div key="date" className="list-cell list-date">
                { dateMillisToString(v.addedOn) }
              </div>

              <div key="size" className="list-cell list-size">
                  { formatByteSize(v.size, 1) }
              </div>

              <div key="resolution" className="list-cell list-resolution">
                { `${v.height}p` }
              </div>

              <div key="actions" className="list-cell list-actions">
                <div className="actions-container">
                  <RiDeleteBin6Line className="delete-action" />
                  <FiDownload className="delete-action" />
                  </div>
              </div>
            </div>  
          );
        })
      }
      </Scrollable>
  );
}

export default ListView