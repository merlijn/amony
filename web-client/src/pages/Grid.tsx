import offset from "@popperjs/core/lib/modifiers/offset";
import { CSSProperties, useEffect, useState } from "react"
import { Api } from "../api/Api";
import { SearchResult, Video } from "../api/Model";
import './Grid.scss'

const Grid = () => {

  const [vids, setVids] = useState<Array<Video>>([])

  useEffect(() => {

    Api.getVideos("", 4, 0).then(response => {
      const newvideos = (response as SearchResult).videos
        setVids(newvideos);
      });
  }, [])

  return (
    <>
      {
        vids.map((v) => {
          return (
             <div className="grid-video-container">
                <video onClick={ (v) => v.currentTarget.play() } controls>
                    <source src={v.video_url} type="video/mp4"/>
                </video>
              </div>)
        })
      }
    </>
  );
}

export default Grid