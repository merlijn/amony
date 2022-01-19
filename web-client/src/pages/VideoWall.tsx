import { useEffect, useState } from "react";
import { Api } from "../api/Api";
import { Fragment } from "../api/Model";
import './VideoWall.scss';

const VideoWall = () => {

  const [fragments, setFragments] = useState<Array<Fragment>>([])

  const [topLeft, setTopLeft] = useState(0)
  const [topRight, setTopRight] = useState(1)
  const [botLeft, setBotLeft] = useState(2)
  const [botRight, setBotRight] = useState(3)

  const getNext = (): number => {
    const max = Math.max(topLeft, topRight, botLeft, botRight);
    return max + 1;
  }

  const getUrl = (idx: number): string => {
    const f = fragments[idx]
    return f.urls[f.urls.length-1]
  }

  useEffect(() => {

    Api.getFragments(32, 0).then(response => {
        setFragments((response as Array<Fragment>));
      });
  }, [])

  return (
    <>
      { topLeft < fragments.length  && <VideoPlyr key={`video-${topLeft}`}  url = { getUrl(topLeft) } onEnded = { () => setTopLeft(getNext()) }/> }
      { topRight < fragments.length && <VideoPlyr key={`video-${topRight}`} url = { getUrl(topRight) } onEnded = { () => setTopRight(getNext()) }/> }
      { botLeft < fragments.length  && <VideoPlyr key={`video-${botLeft}`}  url = { getUrl(botLeft) } onEnded = { () => setBotLeft(getNext()) }/> }
      { botRight < fragments.length && <VideoPlyr key={`video-${botRight}`} url = { getUrl(botRight) } onEnded = { () => setBotRight(getNext()) }/> }
    </>
  );
}

const VideoPlyr = (props: { url?: string, onEnded: () => any }) => {

  const play = (e: HTMLVideoElement) => {
    console.log(`loading: ${props.url}`)
    // e.load()
    e.play()
  }

  return <div className="grid-video-container">
      <video 
        onClick = { (v) => play(v.currentTarget) }
        onEnded = { () => props.onEnded() } autoPlay>
          <source src = { props.url } type="video/mp4"/>
      </video>
  </div>
}

export default VideoWall