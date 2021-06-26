import React from 'react';
import Image from 'react-bootstrap/Image';
import './Thumbnail.scss';

class Props {
  constructor(
      public src: string,
      public link: string,
      public title: string
  ){}
}

class Thumbnail extends React.Component<Props, {}> {

  constructor (props: Props) {
    super(props);
  }

  render = () => {

    const title = this.props.title.substring(this.props.title.length - 40, this.props.title.length - 4)

    return (
       <div className="thumbnail thumbnail-container">
         <a href={this.props.link}>
           <Image className="thumb" src={this.props.src} fluid />
           <div className="bottom-right thumbnail-overlay">{title}</div>
         </a>
       </div>
    );
  };
}

export default Thumbnail;
