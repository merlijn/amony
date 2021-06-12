import React from 'react';

import Container from 'react-bootstrap/Container';
import Row from 'react-bootstrap/Row';
import Col from 'react-bootstrap/Col';
import Image from 'react-bootstrap/Image';

function Gallery() {
  return (
    <Container className="full-width">
      <Row className="full-width">
        <Col md="auto"><Image src="https://picsum.photos/400/300?image=5" /></Col>
        <Col md="auto"><Image src="https://picsum.photos/400/300?image=6" /></Col>
        <Col md="auto"><Image src="https://picsum.photos/400/300?image=7" /></Col>
      </Row>
      <Row className="full-width">
        <Col md="auto"><Image src="https://picsum.photos/400/300?image=8" /></Col>
        <Col md="auto"><Image src="https://picsum.photos/400/300?image=9" /></Col>
        <Col md="auto"><Image src="https://picsum.photos/400/300?image=10" /></Col>
      </Row>
    </Container>
  );
}

export default Gallery;
