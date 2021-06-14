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

    return <Image src={this.props.src} />;
  }
}

export default Thumbnail;
