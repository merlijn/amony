import Plyr from 'plyr';
import React, {useEffect} from 'react';
import './Player.scss';
import {Video} from "../api/Model";
import {getMediaById} from "../api/Api";


const Player = (props: {videoId: string}) => {

  const id = '#video-' + props.videoId
  const videoSrc = '/files/videos/' + props.videoId

  useEffect(() => {

    const id = '#video-' + props.videoId
    const element = document.getElementById(id);

    getMediaById(props.videoId).then(response => {

        const vid = (response as Video)

        if (element) {
          const player = new Plyr(element)
          // autoplay is not allowed https://developers.google.com/web/updates/2017/09/autoplay-policy-changes
          // player.play();
        }
      }
    );
  });

  return (
    <div className="videoContainer">
      <video className="videoPlayer" id={id} playsInline controls>
        <source src={videoSrc} type="video/mp4"/>
      </video>
    </div>
  );
}

export default Player;


