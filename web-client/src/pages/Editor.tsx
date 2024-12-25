import React, {useEffect, useRef, useState} from 'react';
import { Api } from '../api/Api';
import { Resource } from '../api/Model';
import FragmentList from '../components/fragments/FragmentList';
import './Editor.scss';
import {MediaPlayer, MediaPlayerInstance, MediaProvider} from "@vidstack/react";
import { PlyrLayout, plyrLayoutIcons } from '@vidstack/react/player/layouts/plyr';

export type EditFragment = {
  idx: number
  start?: number,
  end?: number,
}

const Editor = (props: {videoId: string}) => {

  const [vid, setVid] = useState<Resource | null>(null)

  useEffect(() => {
    Api.getResourceById(props.videoId).then(resource => {
        setVid(resource)
      }
    );
  }, [props]);

  return (
    <div className="video-background">{ vid && <PlayerView vid={vid} /> }</div>
  );
}

const PlayerView = (props: {vid: Resource}) => {

  const [vid, setVid] = useState(props.vid)
  let player = useRef<MediaPlayerInstance>(null)
  const vidRatio = props.vid.resourceMeta.width / props.vid.resourceMeta.height;
  const id = '#video-' + props.vid.resourceId

  // const [plyr, setPlyr] = useState<Plyr | null>(null)
  const [showFragmentControls, setShowFragmentControls] = useState(false)
  const [fragment, setFragment] = useState<EditFragment>({ idx: -1} )

  const updateThumbnailTimestamp = (e: any) => {
    if (player.current) {
      Api.updateThumbnailTimestamp(vid.resourceId, Math.trunc(player.current.currentTime * 1000)).then (response => {
          Api.getResourceById(vid.resourceId).then(response => {
            setVid((response as Resource))
          })
      })
    }
  }

  const updateFragment = (e: any) => {

    if (player.current) {

     if (fragment.start !== undefined &&
         fragment.end !== undefined) {

       const from = Math.trunc(fragment.start * 1000)
       const to = Math.trunc(fragment.end * 1000)

       if (fragment.idx >= 0 && fragment.idx < vid.clips.length) {
         console.log("updating fragment")
         Api.updateFragment(vid.resourceId, fragment.idx, from, to).then (response => {
           setVid(response as Resource)
         });
       }
       if (fragment.idx === vid.clips.length) {
         console.log("adding fragment")
         Api.addFragment(vid.resourceId, from, to).then (response => {
           setVid(response as Resource)
         });
       }
     }
    }
  }

  const seek = (to?: number) => {
    if (player.current && to) {
      player.current.currentTime = to
    }
  }

  const forwards = (amount: number) => {
    if (player.current) {
      player.current.currentTime = player.current.currentTime  + amount
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
        <button className="button-blue" onClick={(e) => seek(fragment.start)}>|&lt;</button>
        <button className={fragment.start ? "button-green" : "button-orange"}
                onClick={(e) => setFragment({...fragment, start: player.current?.currentTime})}>o&lt;</button>
        <button className="button-green" onClick={updateFragment}>o</button>
        <button className={fragment.end ? "button-green" : "button-orange"}
                onClick={(e) => setFragment({...fragment, end: player.current?.currentTime})}>&gt;o
        </button>
        <button className="button-blue" onClick={(e) => seek(fragment.end)}>&gt;|</button>
        <button className="overlay-button" onClick={(e) => forwards(0.1)}>+.1s</button>
        <button className="overlay-button" onClick={(e) => forwards(1)}>+1s</button>
        <button className="button-green" onClick={updateThumbnailTimestamp}>o</button>
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
        <div key={`video-${vid.resourceId}-player`} style={videoSize} className="video-container">
        <MediaPlayer
            className = "video-player"
            tab-index = '-1'
            playsInline
            id = {id}
            ref = { player }
            src = { { src: props.vid.urls.originalResourceUrl, type: "video/mp4"  } }
            title = { props.vid.userMeta.title }
            controlsDelay = { 5000 }
            autoPlay = { false }
            viewType = "video"
            onDestroy = { () => console.log('destroyed') }
        >
            <MediaProvider />
            <PlyrLayout
                icons = { plyrLayoutIcons }
                clickToFullscreen = { false }
                invertTime = { false }
            />
            {/*<DefaultVideoLayout*/}
            {/*    icons = { defaultLayoutIcons }*/}
            {/*/>*/}
        </MediaPlayer>
          { showFragmentControls && fragmentPickingControls }
        </div>

        <div key={`video-${vid.resourceId}-fragments`} style={ { width: fragmentWidth, height: videoSize.height }} className="fragments-container">
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
