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

  let nrows = 3;
  let ncols = 3;

  var rows=[];

    for (var i =0; i < nrows; i++) {
        var cols=[];

        for (var j=0; j < ncols; j++) {
          let imgSrc = `https://picsum.photos/400/300?image=${getRandomInt(1, 200)}`;

           cols.push(
             <Col md="auto"><Image src={imgSrc} /></Col>
           );
        }

        rows.push(
            <Row> { cols } </Row>
        );
    }

  return (
    <Container fluid>
       { rows }
    </Container>
  );
}

export default Gallery;
