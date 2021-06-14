import React from 'react';
import Container from 'react-bootstrap/Container';
import Row from 'react-bootstrap/Row';
import Col from 'react-bootstrap/Col';
import { doGET } from '../api/Api';
import { Video } from '../api/Model';
import Thumbnail from './Thumbnail';

function getRandomInt(min: number, max: number) {
    min = Math.ceil(min);
    max = Math.floor(max);
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

class State {
  constructor(
    public movies: Video[]
  ) {}
}

class Gallery extends React.Component<{}, State> {

  constructor (props: any) {
    super(props);

  let width: number = 459;
  let height: number = Math.ceil(width / 16 * 9);

    this.state = { movies: [
      {
         title: "foo",
         thumbnail: `https://picsum.photos/${width}/${height}?image=1`,
         id: "1"
      },
      {
           title: "foo",
           thumbnail: `https://picsum.photos/${width}/${height}?image=2`,
           id: "1"
        },
       {
         title: "foo",
         thumbnail: `https://picsum.photos/${width}/${height}?image=3`,
         id: "1"
      }
    ] };
  }

  componentDidMount () {

    doGET('/movie/foo')
      .then(json => {

//          let movie = deserialize<Movie>(json, Movie);
//          this.setState({ text: json.title });
      });
  }

  render() {

      let ncols: number = 3;
      let nrows: number = Math.ceil(this.state.movies.length / ncols);

      let rows = [...new Array(nrows)].map((e, y) => {
          let cols = [...new Array(ncols)].map((e, x) => {

              let idx: number = y * x + x;

              let clazz = x === 0 ? "gallery-column-first" : "gallery-column"

              if (idx <= this.state.movies.length) {
                  let movie: Video = this.state.movies[y * x + x];
                  let m: string = movie.thumbnail;
                  return <Col md="auto" className={clazz}><Thumbnail src={m} link="" /></Col>;
              }
              else {
                   return <Col md="auto" className={clazz}></Col>;
              }

          });
          return <Row className="gallery-row"> { cols } </Row>;
      });

      return (
        <Container fluid>
           { rows }
        </Container>
      );
  }
}

export default Gallery;
