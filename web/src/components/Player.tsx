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

type EditFragment = {
  idx: number
  start?: number,
  end?: number,
}

const PlayerView = (props: {vid: Video}) => {

  const vidRatio = props.vid.resolution_x / props.vid.resolution_y;
  const id = '#video-' + props.vid.id
  const videoSrc = '/files/videos/' + props.vid.id

  const [plyr, setPlyr] = useState<Plyr | null>(null)
  const [showFragmentControls, setShowFragmentControls] = useState(false)

  const [fragment, setFragment] = useState<EditFragment>({ idx: -1} )

  useEffect(() => {
    const element = document.getElementById(id);
    if (element) {
      const plyr = new Plyr(element, { fullscreen : { enabled: false }})

      setPlyr(plyr)
    }
  }, [props]);

  const setThumbnail = (e: any) => {

    if (plyr) {

     if (fragment.start != undefined &&
         fragment.end != undefined) {

       const from = Math.trunc(fragment.start * 1000)
       const to = Math.trunc(fragment.end * 1000)

       if (fragment.idx >= 0 && fragment.idx < props.vid.previews.length) {
         console.log("updating fragment")
         Api.updateFragment(props.vid.id, fragment.idx, from, to).then (response => {
           // done
         });
       }
       if (fragment.idx == props.vid.previews.length) {
         console.log("adding fragment")
         Api.addFragment(props.vid.id, from, to).then (response => {
           // done
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
      <Button variant="success" size="sm" onClick={setThumbnail}>o</Button>
      <Button variant={fragment.end ? "success" : "warning"} size="sm" onClick={(e) => setFragment({ ...fragment, end: plyr?.currentTime }) }>&gt;o</Button>
      <Button size="sm" onClick={(e) => seek(fragment.end) }>&gt;|</Button>
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
          <FragmentList vid={props.vid} selected={fragment.idx} selectFn={selectFragment} />
        </div>
      </div>
  );
}


const FragmentList = (props: {vid: Video, selected: number, selectFn: (f: EditFragment) => any}) => {

  const ratio = (props.vid.resolution_x / props.vid.resolution_y).toFixed(2);

  const deleteFragment = (idx: number) => {

    if (props.vid.previews.length > 1) {
      console.log(`Deleting fragment ${props.vid.id}:${idx}`)
      Api.deleteFragment(props.vid.id, idx)
    }
  }

  const fragmentList =
    props.vid.previews.map((f, idx) => {

      return(
        <div key={`fragment-${props.vid.id}-${f.timestamp_start}`} className="fragment-container">
          <video style={ { width: "100%"} }
                 className={ props.selected == idx ? "fragment-selected" : "fragment" } muted
                 onMouseEnter={(e) => e.currentTarget.play() }
                 onMouseLeave={(e) => e.currentTarget.pause() }
                 onClick={(e) => {
                   props.selectFn({ idx: idx, start: f.timestamp_start / 1000, end: f.timestamp_end / 1000 })
                  }
                 }>
            <source src={f.uri} type="video/mp4"/>
          </video>
          <div className="bottom-left duration-overlay">{durationInMillisToString(f.timestamp_start)}</div>
          {
            props.vid.previews.length > 1 &&
            (<div className="top-right menu-icon">
              <img onClick={ (e) => deleteFragment(idx)} src="/cancel_black_24dp.svg" />
            </div>)
          }

        </div>
      );
    })

  const height = `calc(15vw * 1 / ${ratio})`
  const sizing = { width: "100%", height: height, "line-height": height }

  return (
    <div>
      { fragmentList }
      <div key="new-fragment"
           style={sizing}
           className="new-fragment"
           onClick={(e) => {
             props.selectFn({ idx: props.vid.previews.length })
            }
           }>
        +
      </div>
    </div>
  );
}


export default Player;


