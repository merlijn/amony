import Plyr from 'plyr';
import React from "react";

class Props {
  constructor(
    public videoId: string
  ){}
}

class Player extends React.Component<Props, {}> {

  constructor (props: Props) {
    super(props);
  }

  render = () => {

    const id = '#vid-' + this.props.videoId
    const player = new Plyr(id);
    const videoSrc = '/files/videos/' + this.props.videoId;

    return (
      <video id={id} playsInline controls>
        <source src={videoSrc} type="video/mp4"/>
      </video>
    );
  };
}

export default Player;


