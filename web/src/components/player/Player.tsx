import Plyr from 'plyr';
import React, {useEffect, useState} from 'react';
import './Player.scss';
import {Video} from "../../api/Model";
import {Api} from "../../api/Api";
import {Button} from "react-bootstrap";
import FragmentList from "./FragmentList";

export type EditFragment = {
  idx: number
  start?: number,
  end?: number,
}

const Player = (props: {videoId: string}) => {

  const [vid, setVid] = useState<Video | null>(null)

  useEffect(() => {
    Api.getMediaById(props.videoId).then(response => {
        setVid((response as Video))
      }
    );
  }, [props]);

  return (
    <div className="video-background">{ vid && <PlayerView vid={vid} /> }</div>
  );
}

const PlayerView = (props: {vid: Video}) => {

  const [vid, setVid] = useState(props.vid)
  const vidRatio = props.vid.resolution_x / props.vid.resolution_y;
  const id = '#video-' + props.vid.id
  const videoSrc = '/files/videos/' + props.vid.id

  const [plyr, setPlyr] = useState<Plyr | null>(null)
  const [showFragmentControls, setShowFragmentControls] = useState(false)
  const [fragment, setFragment] = useState<EditFragment>({ idx: -1} )

  useEffect(() => {
    const element = document.getElementById(id);
    if (element) {
      const plyr = new Plyr(element, { fullscreen : { enabled: true }, invertTime: false})
      setPlyr(plyr)
    }
  }, [props]);

  const updateFragment = (e: any) => {

    if (plyr) {

     if (fragment.start !== undefined &&
         fragment.end !== undefined) {

       const from = Math.trunc(fragment.start * 1000)
       const to = Math.trunc(fragment.end * 1000)

       if (fragment.idx >= 0 && fragment.idx < vid.fragments.length) {
         console.log("updating fragment")
         Api.updateFragment(vid.id, fragment.idx, from, to).then (response => {
           setVid(response as Video)
         });
       }
       if (fragment.idx === vid.fragments.length) {
         console.log("adding fragment")
         Api.addFragment(vid.id, from, to).then (response => {
           setVid(response as Video)
         });
       }
     }
    }
  }

  const seek = (to?: number) => {
    if (plyr && to) {
      plyr.currentTime = to
    }
  }

  const forwards = (amount: number) => {
    if (plyr) {
      plyr.currentTime = plyr.currentTime + amount
    }
  }

  const selectFragment = (f: EditFragment) => {

    setFragment(f)
    setShowFragmentControls(true)

    if (f.start)
      seek(f.start)
  }

  const fragmentPickingControls =
    <div className="fragment-picker">
      <Button size="sm" onClick={(e) => forwards(-1)}>-1s</Button>
      <Button size="sm" onClick={(e) => forwards(-0.1)}>-.1ms</Button>
      <Button size="sm" onClick={(e) => seek(fragment.start) }>|&lt;</Button>
      <Button variant={fragment.start ? "success" : "warning"} size="sm" onClick={(e) => setFragment({ ...fragment, start: plyr?.currentTime }) }>o&lt;</Button>
      <Button variant="success" size="sm" onClick={updateFragment}>o</Button>
      <Button variant={fragment.end ? "success" : "warning"} size="sm" onClick={(e) => setFragment({ ...fragment, end: plyr?.currentTime }) }>&gt;o</Button>
      <Button size="sm" onClick={(e) => seek(fragment.end) }>&gt;|</Button>
      <Button size="sm" onClick={(e) => forwards(0.1)}>+.1s</Button>
      <Button size="sm" onClick={(e) => forwards(1)}>+1s</Button>
    </div>

  const maxWidth = "80vw"
  const maxHeight = "80vh"
  const fragmentWidth = "20vw"

  const videoSize = {
    width: `min(${maxWidth}, ${maxHeight} * ${vidRatio})`,
    height: `min(${maxHeight}, ${maxWidth} * 1 / ${vidRatio})`
  }

  const totalWidth = `calc(${videoSize.width} + ${fragmentWidth})`

  return (
      <div style = { { width: totalWidth, height: videoSize.height } } className="abs-center">
        <div key={`video-${vid.id}-player`} style={videoSize} className="video-container">
          <video className="video-player" id={id} playsInline controls>
            <source src={videoSrc} type="video/mp4"/>
          </video>
          { showFragmentControls && fragmentPickingControls }
        </div>

        <div key={`video-${vid.id}-fragments`} style={ { width: fragmentWidth, height: videoSize.height }} className="fragments-container">
          <FragmentList vid={vid} selected={fragment.idx} selectFn={selectFragment} setVid={(v) => { setVid(v); setFragment({ idx: -1}) }} />
        </div>
      </div>
  );
}

export default Player;