import React from 'react';
import Image from 'react-bootstrap/Image';
import './Thumbnail.scss';

const durationString = (duration: number) => {

  const secondsInMillis = 1000;
  const minutesInMilis = 1000 * 60;
  const hoursInMillis = minutesInMilis * 60;

  const hours = Math.trunc(duration / hoursInMillis)
  const minutes = Math.trunc(duration % hoursInMillis / minutesInMilis)
  const seconds = Math.trunc(duration % minutesInMilis / secondsInMillis)

  let durationStr = ""

  if (hours > 0) {
    durationStr += `${hours}:`
  }

  durationStr += `${minutes}:`

  if (seconds < 10)
    durationStr += '0'

  durationStr += `${seconds}`

  return durationStr
}

const Thumbnail = (props: {src: string, link: string, title: string, duration: number}) => {

  const durationStr = durationString(props.duration)

  return (
    <div className="thumbnail thumbnail-container">
      <a href={props.link}>
        <Image className="thumb" src={props.src} fluid />
        <div className="bottom-right">{props.title}</div>
        <div className="bottom-left">{durationStr}</div>
      </a>
    </div>
  );
}

export default Thumbnail;
