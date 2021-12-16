import { useEffect, useState } from "react"
import { FaSort } from "react-icons/fa"
import ProgressiveImage from "react-progressive-graceful-image"
import { Api } from "../api/Api"
import { MediaSelection, SearchResult, Video, VideoMeta } from "../api/Model"
import { dateMillisToString } from "../api/Util"
import './ListView.scss'
import TagEditor from "./shared/TagEditor"

type ListProps = {
  selection: MediaSelection
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
    <div className="list-container">
      <div className="list-row">
        <div className="list-cell"></div>
        <div className="list-cell"></div>
        <div className="list-cell list-header"><FaSort className="column-sort-icon" /></div>
        <div className="list-cell list-header"><FaSort className="column-sort-icon" /></div>
        <div className="list-cell list-header"><FaSort className="column-sort-icon" /></div>
        <div className="list-cell">Tags</div>
      </div>
      {
        searchResult.videos.map((v) => {
          return(
            <div key={`row-${v.id}`} className="list-row">
              <div key="select" className="list-cell list-select">
                <input type="checkbox" />
              </div>

              <div className="list-cell list-thumbnail">
              <ProgressiveImage src={v.thumbnail_url} placeholder="/image_placeholder.svg">
                  { (src: string) => <img className="list-thumbnail-img" src={src} alt="an image" /> }
              </ProgressiveImage>
              </div>
              <div className="list-cell list-date">
                { dateMillisToString(v.addedOn) }
              </div>
              <div className="list-cell list-resolution">
                {`${v.height}p`}
              </div>
              <div className="list-cell list-title">
                {v.meta.title}
              </div>
              
              <div className="list-cell list-tags">
                <TagEditor tags={v.meta.tags} callBack = { (tags) => { updateTags(v, tags) } } />
              </div>
            </div>  
          );
        })
      }
    </div>
  );
}

export default ListView