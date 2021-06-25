import React from 'react';
import Image from 'react-bootstrap/Image';

class Props {

  src?: string;
  link?: string;
}

class Thumbnail extends React.Component<Props, {}> {

  constructor (props: Props) {
    super(props);
  }

  render() {

    return <a href={this.props.link}><Image className="thumbnail" src={this.props.src} fluid /></a>;
  }
}

export default Thumbnail;
