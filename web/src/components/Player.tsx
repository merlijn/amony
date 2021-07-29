import Plyr from 'plyr';
import React, {useEffect, useState} from 'react';
import './Player.scss';
import {Fragment, Video} from "../api/Model";
import {Api} from "../api/Api";
import {useWindowSize} from "../api/Util";
import {Button} from "react-bootstrap";

const Player = (props: {videoId: string}) => {

  const id = '#video-' + props.videoId
  const videoSrc = '/files/videos/' + props.videoId

  const windowSize = useWindowSize(((oldSize, newSize) => Math.abs(newSize.width - oldSize.width) > 5));
  const [videoStyle, setVideoStyle] = useState({ width: "640", height: "288" })
  const [plyr, setPlyr] = useState<Plyr | null>(null)
  const [vid, setVid] = useState<Video | null>(null)

  useEffect(() => {

    const id = '#video-' + props.videoId
    const element = document.getElementById(id);

    Api.getMediaById(props.videoId).then(response => {

        const vid = (response as Video)
        const vidRatio = vid.resolution_x / vid.resolution_y;

        setVideoStyle({
          width: `min(80vw, 80vh * ${vidRatio})`,
          height: `min(80vh, 80vw * 1 / ${vidRatio})`
        })

        if (element) {
          setPlyr(new Plyr(element))
        }

        setVid(vid)
      }
    );
  }, [props]);


  let startTime: number | undefined = 0
  let endTime: number | undefined = 0

  const setThumbnail = (e: any) => {
    if (plyr && vid && startTime && endTime) {

      const from = Math.trunc(startTime * 1000)
      const to = Math.trunc(endTime * 1000)

      Api.addFragment(vid.id, from, to).then (response => {
        // done
      });
    }
  }

  const seek = (to?: number) => {
    if (plyr && to) {
      plyr.currentTime = to
    }
  }

  const forwards = (amount: number) => {
    if (plyr && vid) {
      plyr.currentTime = plyr.currentTime + amount
    }
  }

  const selectFragment = (f: Fragment) => {
    seek(f.timestamp_start / 1000)
  }

  return (
    <div className="videoBackground">
      <div style={videoStyle} className="videoContainer">
        <video className="videoPlayer" id={id} playsInline controls>
          <source src={videoSrc} type="video/mp4"/>
        </video>

        <div className="fragment-picker">
          <Button size="sm" onClick={(e) => forwards(-1)}>-1s</Button>
          <Button size="sm" onClick={(e) => forwards(-0.1)}>-.1ms</Button>
          <Button size="sm" onClick={(e) => seek(startTime) }>|&lt;</Button>
          <Button variant={startTime ? "success" : "warning"} size="sm" onClick={(e) => startTime = plyr?.currentTime }>o&lt;</Button>
          <Button variant="success" size="sm" onClick={setThumbnail}>o</Button>
          <Button variant={endTime ? "success" : "warning"} size="sm" onClick={(e) => endTime = plyr?.currentTime}>&gt;o</Button>
          <Button size="sm" onClick={(e) => seek(endTime) }>&gt;|</Button>
          <Button size="sm" onClick={(e) => forwards(0.1)}>+.1s</Button>
          <Button size="sm" onClick={(e) => forwards(1)}>+1s</Button>
        </div>
      </div>
      {
        vid && <Fragments vid={vid} selectFn={selectFragment} />
      }
    </div>
  );
}

const Fragments = (props: {vid: Video, selectFn: (f: Fragment) => any}) => {

  const [selected, setSelected] = useState(-1)
  const ratio = (props.vid.resolution_x / props.vid.resolution_y).toFixed(2);
  const height = `min(80vh, 80vw * 1 / ${ratio})`

  const fheight = `calc(15vw * 1 / ${ratio})`

  const sizing = { width: "100%", height: fheight, "line-height": fheight }

  const fragmentList =
    props.vid.previews.map((f, idx) => {
      return(
        <div>
          <video style={ { width: "100%"} }
                 key={`fragment-${f.timestamp_start}`}
                 className={ selected == idx ? "fragment-selected" : "fragment" } muted
                 onMouseEnter={(e) => e.currentTarget.play() }
                 onMouseLeave={(e) => e.currentTarget.pause() }
                 onClick={(e) => { setSelected(idx); props.selectFn(f) } }>
            <source src={f.uri} type="video/mp4"/>
          </video>
          <div className="top-right menu-icon"><img src="/cancel_black_24dp.svg" /></div>
        </div>
      );
    })

  return (
    <div style={ { width: "15vw", height: height }} className="fragment-list">
      { fragmentList }
      <div key="new-fragment" style={sizing} className="new-fragment">+</div>
    </div>
  );
}


export default Player;


