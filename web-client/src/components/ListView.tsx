import { useEffect, useState } from "react"
import ProgressiveImage from "react-progressive-graceful-image"
import { Api } from "../api/Api"
import { SearchResult, Video } from "../api/Model"
import { dateMillisToString, durationInMillisToString } from "../api/Util"
import { MediaSelection } from "./Gallery"
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
    const n      = 20

    if (n > 0 && fetchMore) {
      Api.getVideos(
        props.selection.query || "",
        n,
        offset,
        props.selection.tag,
        props.selection.playlist,
        props.selection.minimumQuality,
        props.selection.sort).then(response => {

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

  return (
    <div className="list-container">
      {
        searchResult.videos.map((v) => {
          return(
            <div className="list-row">
              <div className="list-cell list-thumbnail">
              <ProgressiveImage src={v.thumbnail_url} placeholder="/image_placeholder.svg">
                  { (src: string) => <img className="list-thumbnail" src={src} alt="an image" /> }
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
                <TagEditor tags={v.meta.tags} callBack={() => {} } />
              </div>
            </div>  
          );
        })
      }
    </div>
  );
}

export default ListView