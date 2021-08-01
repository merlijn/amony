import Plyr from 'plyr';
import React, {CSSProperties, useEffect, useState} from 'react';
import './Player.scss';
import {Video} from "../api/Model";
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
    <div className="video-background">
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
      const plyr = new Plyr(element, { fullscreen : { enabled: true }})
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

  const maxVideoWidth = "75vw"
  const maxVideoHeight = "75vh"

  const videoStyle = {
    width: `min(${maxVideoWidth}, ${maxVideoHeight} * ${vidRatio})`,
    height: `min(${maxVideoHeight}, ${maxVideoWidth} * 1 / ${vidRatio})`
  }

  const fragmentWidth = "20vw"
  const totalWidth = `calc(min(${maxVideoWidth}, ${maxVideoHeight} * ${vidRatio}) + 20vw)`
  const sharedHeight = `min(${maxVideoHeight}, ${maxVideoWidth} * 1 / ${vidRatio})`

  return (
      <div style = { { width: totalWidth, height: sharedHeight } } className="abs-center">
        <div key={`video-${vid.id}-player`} style={videoStyle} className="video-container">
          <video className="video-player" id={id} playsInline controls>
            <source src={videoSrc} type="video/mp4"/>
          </video>
          { showFragmentControls && fragmentPickingControls }
        </div>

        <div key={`video-${vid.id}-fragments`} style={ { width: fragmentWidth, height: sharedHeight }} className="fragment-list">
          <FragmentList vid={vid} selected={fragment.idx} selectFn={selectFragment} setVid={(v) => { setVid(v); setFragment({ idx: -1}) }} />
        </div>
      </div>
  );
}


const FragmentList = (props: {vid: Video, selected: number, selectFn: (f: EditFragment) => any, setVid: (vid: Video) => any}) => {

  const ratio = (props.vid.resolution_x / props.vid.resolution_y).toFixed(2);

  const deleteFragment = (idx: number) => {

    if (props.vid.fragments.length > 1) {
      console.log(`Deleting fragment ${props.vid.id}:${idx}`)
      Api.deleteFragment(props.vid.id, idx).then((result) => {
        props.setVid(result as Video)
      })
    }
  }

  const extraStyle = (idx: number) => {
    let extraStyle: CSSProperties = { }

    if (props.selected >=0 && idx === props.selected - 1)
      extraStyle = { marginBottom: 0 }
    if (props.selected >=0 && idx === props.selected + 1)
      extraStyle = { marginTop: 0 }

    return extraStyle;
  }

  const fragmentList =
    props.vid.fragments.map((f, idx) => {

      return(
        <div style={ extraStyle(idx) } key={`fragment-${props.vid.id}-${f.timestamp_start}`} className={ (props.selected == idx ? "fragment-selected" : "fragment-not-selected") + " fragment-container" }>

          <video muted
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
            props.vid.fragments.length > 1 &&
            (<div className="delete-fragment-icon">
              <img onClick={ (e) => deleteFragment(idx)} src="/cancel_black_24dp.svg" />
            </div>)
          }
        </div>
      );
    })

  const sizing = {
    "--foo": "10px",
    height: `calc(20vw * 1 / ${ratio})`,
    lineHeight: `calc(20vw * 1 / ${ratio})`
  }

  const n = props.vid.fragments.length

  return (
    <div>
      { fragmentList }
      <div key={`fragment-${props.vid.id}-new`}
           style={ extraStyle(n) }
           className={ (props.selected == n ? "fragment-selected" : "fragment-not-selected") + " fragment-container" }
           onClick={(e) => {
             props.selectFn({ idx: n })
            }
           }>
        <div style= { sizing } className="new-fragment">+</div>
      </div>
    </div>
  );
}


export default Player;


