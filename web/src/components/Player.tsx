import Plyr from 'plyr';
import React from 'react';
import { useEffect } from 'react';
import './Player.scss';

class Props {
  constructor(
    public videoId: string
  ){}
}

class Player extends React.Component<Props, {}> {

  constructor (props: Props) {
    super(props);
  }

  componentDidMount = () => {
    const id = '#video-' + this.props.videoId

    const element = document.getElementById(id);

    if (element) {
      console.log("created player")
      const player = new Plyr(element)
      console.log("playing video")
      // autoplay is not allowed https://developers.google.com/web/updates/2017/09/autoplay-policy-changes
      // player.play();
    }
  };

  render = () => {

    const id = '#video-' + this.props.videoId
    const videoSrc = '/files/videos/' + this.props.videoId

    return (
      <div className="videoContainer">
        <video className="videoPlayer" id={id} playsInline controls>
          <source src={videoSrc} type="video/mp4"/>
        </video>
      </div>
    );
  };
}

export default Player;


