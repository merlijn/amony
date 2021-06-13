import React from 'react';

import Container from 'react-bootstrap/Container';
import Row from 'react-bootstrap/Row';
import Col from 'react-bootstrap/Col';
import Image from 'react-bootstrap/Image';

function getRandomInt(min: number, max: number) {
    min = Math.ceil(min);
    max = Math.floor(max);
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

function Gallery() {

  let nrows: number = 3;
  let ncols: number = 3;
  let width: number = 459;
  let height: number = Math.ceil(width / 16 * 9);

  let rows = [...new Array(nrows)].map((e, idx) => {
      let cols = [...new Array(ncols)].map((e, idx) => {
              let imgSrc = `https://picsum.photos/${width}/${height}?image=${getRandomInt(1, 100)}`;
              let clazz = idx === 0 ? "gallery-column-first" : "gallery-column"

              return <Col md="auto" className={clazz}><Image src={imgSrc} /></Col>;
      });
      return <Row className="gallery-row"> { cols } </Row>;
  });

  return (
    <Container fluid>
       { rows }
    </Container>
  );
}

export default Gallery;
