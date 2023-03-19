import Plyr from 'plyr';
import React, { useEffect, useState } from 'react';
import { Api } from '../api/Api';
import { Media } from '../api/Model';
import FragmentList from '../components/fragments/FragmentList';
import './Editor.scss';

export type EditFragment = {
  idx: number
  start?: number,
  end?: number,
}

const Editor = (props: {videoId: string}) => {

  const [vid, setVid] = useState<Media | null>(null)

  useEffect(() => {
    Api.getMediaById(props.videoId).then(response => {
        setVid((response as Media))
      }
    );
  }, [props]);

  return (
    <div className="video-background">{ vid && <PlayerView vid={vid} /> }</div>
  );
}

const PlayerView = (props: {vid: Media}) => {

  const [vid, setVid] = useState(props.vid)
  const vidRatio = props.vid.mediaInfo.width / props.vid.mediaInfo.height;
  const id = '#video-' + props.vid.id

  const [plyr, setPlyr] = useState<Plyr | null>(null)
  const [showFragmentControls, setShowFragmentControls] = useState(false)
  const [fragment, setFragment] = useState<EditFragment>({ idx: -1} )

  useEffect(() => {
    const element = document.getElementById(id);
    if (element) {
      const plyr = new Plyr(element, { fullscreen : { enabled: false }, invertTime: false})
      setPlyr(plyr)
    }
  }, [props]);

  const updateFragment = (e: any) => {

    if (plyr) {

     if (fragment.start !== undefined &&
         fragment.end !== undefined) {

       const from = Math.trunc(fragment.start * 1000)
       const to = Math.trunc(fragment.end * 1000)

       if (fragment.idx >= 0 && fragment.idx < vid.highlights.length) {
         console.log("updating fragment")
         Api.updateFragment(vid.id, fragment.idx, from, to).then (response => {
           setVid(response as Media)
         });
       }
       if (fragment.idx === vid.highlights.length) {
         console.log("adding fragment")
         Api.addFragment(vid.id, from, to).then (response => {
           setVid(response as Media)
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
      <button className="overlay-button" onClick={(e) => forwards(-1)}>-1s</button>
      <button className="overlay-button" onClick={(e) => forwards(-0.1)}>-.1ms</button>
      <button className="button-blue" onClick={(e) => seek(fragment.start) }>|&lt;</button>
      <button className={fragment.start ? "button-green" : "button-orange"} onClick={(e) => setFragment({ ...fragment, start: plyr?.currentTime }) }>o&lt;</button>
      <button className="button-green" onClick = { updateFragment }>o</button>
      <button className={fragment.end ? "button-green" : "button-orange"} onClick={(e) => setFragment({ ...fragment, end: plyr?.currentTime }) }>&gt;o</button>
      <button className="button-blue" onClick={(e) => seek(fragment.end) }>&gt;|</button>
      <button className="overlay-button" onClick={(e) => forwards(0.1)}>+.1s</button>
      <button className="overlay-button" onClick={(e) => forwards(1)}>+1s</button>
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
            <source src={props.vid.urls.originalResourceUrl} type="video/mp4"/>
          </video>
          { showFragmentControls && fragmentPickingControls }
        </div>

        <div key={`video-${vid.id}-fragments`} style={ { width: fragmentWidth, height: videoSize.height }} className="fragments-container">
          <FragmentList 
            vid      = {vid} 
            selected = {fragment.idx} 
            selectFn = {selectFragment} 
            setVid   = {(v) => { setVid(v); setFragment({ idx: -1}) }} />
        </div>
      </div>
  );
}

export default Editor;
