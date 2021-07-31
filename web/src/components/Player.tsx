import Plyr from 'plyr';
import React, {useEffect, useState} from 'react';
import './Player.scss';
import {Fragment, Video} from "../api/Model";
import {Api} from "../api/Api";
import {Button} from "react-bootstrap";
import {durationInMillisToString} from "../api/Util";

const Player = (props: {videoId: string}) => {

  const [vid, setVid] = useState<Video | null>(null)

  useEffect(() => {
    Api.getMediaById(props.videoId).then(response => {
        setVid((response as Video))
      }
    );
  }, [props]);

  return (
    <div className="videoBackground">
      { vid && <PlayerView vid={vid} /> }
    </div>
  );
}

const PlayerView = (props: {vid: Video}) => {

  const vidRatio = props.vid.resolution_x / props.vid.resolution_y;
  const id = '#video-' + props.vid.id
  const videoSrc = '/files/videos/' + props.vid.id

  const [plyr, setPlyr] = useState<Plyr | null>(null)
  const [showFragmentControls, setShowFragmentControls] = useState(false)

  const [fragmentStart, setFragmentStart] = useState<number | undefined>(undefined)
  const [fragmentEnd, setFragmentEnd] = useState<number | undefined>(undefined)
  const [fragmentIdx, setFragmentIdx] = useState<number | undefined>(undefined)

  useEffect(() => {
    const element = document.getElementById(id);
    if (element) {
      const plyr = new Plyr(element, { fullscreen : { enabled: false }})

      setPlyr(plyr)
    }
  }, [props]);

  const setThumbnail = (e: any) => {

    if (plyr) {

     if (fragmentIdx != undefined &&
         fragmentStart != undefined &&
         fragmentEnd != undefined) {
       console.log("update fragment")

       const from = Math.trunc(fragmentStart * 1000)
       const to = Math.trunc(fragmentEnd * 1000)

       console.log("setting fragment")
       console.log(from)
       console.log(to)

       Api.updateFragment(props.vid.id, fragmentIdx, from, to).then (response => {
         // done
       });
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

  const selectFragment = (f: Fragment, idx: number) => {

    setFragmentIdx(idx)
    setFragmentStart(f.timestamp_start / 1000)
    setFragmentEnd(f.timestamp_end / 1000)
    setShowFragmentControls(true)
    seek(f.timestamp_start / 1000)
  }

  const fragmentPickingControls =
    <div className="fragment-picker">
      <Button size="sm" onClick={(e) => forwards(-1)}>-1s</Button>
      <Button size="sm" onClick={(e) => forwards(-0.1)}>-.1ms</Button>
      <Button size="sm" onClick={(e) => seek(fragmentStart) }>|&lt;</Button>
      <Button variant={fragmentStart ? "success" : "warning"} size="sm" onClick={(e) => setFragmentStart(plyr?.currentTime) }>o&lt;</Button>
      <Button variant="success" size="sm" onClick={setThumbnail}>o</Button>
      <Button variant={fragmentEnd ? "success" : "warning"} size="sm" onClick={(e) => setFragmentEnd(plyr?.currentTime)}>&gt;o</Button>
      <Button size="sm" onClick={(e) => seek(fragmentEnd) }>&gt;|</Button>
      <Button size="sm" onClick={(e) => forwards(0.1)}>+.1s</Button>
      <Button size="sm" onClick={(e) => forwards(1)}>+1s</Button>
    </div>

  const videoStyle = {
    width: `min(80vw, 80vh * ${vidRatio})`,
    height: `min(80vh, 80vw * 1 / ${vidRatio})`
  }

  const fragmentWidth = "15vw"
  const totalWidth = `calc(min(80vw, 80vh * ${vidRatio}) + 15vw)`
  const videoWidth = `min(80vw, 80vh * ${vidRatio})`
  const sharedHeight = `min(80vh, 80vw * 1 / ${vidRatio})`

  return (
      <div style = { { width: totalWidth, height: sharedHeight } } className="editor-container">
        <div key={`video-${props.vid.id}-player`} style={videoStyle} className="videoContainer">
          <video className="videoPlayer" id={id} playsInline controls>
            <source src={videoSrc} type="video/mp4"/>
          </video>
          { showFragmentControls && fragmentPickingControls }
        </div>

        <div key={`video-${props.vid.id}-fragments`} style={ { width: fragmentWidth, height: sharedHeight }} className="fragment-list">
          <FragmentList vid={props.vid} selectFn={selectFragment} />
        </div>
      </div>
  );
}


const FragmentList = (props: {vid: Video, selectFn: (f: Fragment, idx: number) => any}) => {

  const [selected, setSelected] = useState(-1)
  const ratio = (props.vid.resolution_x / props.vid.resolution_y).toFixed(2);
  const height = `min(80vh, 80vw * 1 / ${ratio})`

  const fheight = `calc(15vw * 1 / ${ratio})`

  const sizing = { width: "100%", height: fheight, "line-height": fheight }


  const fragmentList =
    props.vid.previews.map((f, idx) => {

      return(
        <div key={`fragment-${props.vid.id}-${f.timestamp_start}`} className="fragment-container">
          <video style={ { width: "100%"} }
                 className={ selected == idx ? "fragment-selected" : "fragment" } muted
                 onMouseEnter={(e) => e.currentTarget.play() }
                 onMouseLeave={(e) => e.currentTarget.pause() }
                 onClick={(e) => { setSelected(idx); props.selectFn(f, idx) } }>
            <source src={f.uri} type="video/mp4"/>
          </video>
          <div className="bottom-left duration-overlay">{durationInMillisToString(f.timestamp_start)}</div>
          <div className="top-right menu-icon"><img src="/cancel_black_24dp.svg" /></div>
        </div>
      );
    })

  return (
    <div>
      { fragmentList }
      <div key="new-fragment" style={sizing} className="new-fragment">+</div>
    </div>
  );
}


export default Player;


