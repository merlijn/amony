import React from 'react';
import Container from 'react-bootstrap/Container';
import Row from 'react-bootstrap/Row';
import Col from 'react-bootstrap/Col';
import { doGET } from '../api/Api';
import { Video } from '../api/Model';
import Thumbnail from './Thumbnail';
import Pagination from 'react-bootstrap/Pagination';
import { deserialize } from 'typescript-json-serializer';

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

  constructor (props: {}) {

  super(props);

      let width: number = 459;
      let height: number = Math.ceil(width / 16 * 9);

    this.state = { movies : [] };
  }

  componentDidMount () {

    doGET('/api/movies')
      .then(videos => {
         this.setState({ movies: videos });
      });
  }

  render() {

      let ncols: number = 3;
      let nrows: number = Math.ceil(this.state.movies.length / ncols);

      let rows = [...new Array(nrows)].map((e, y) => {
          let cols = [...new Array(ncols)].map((e, x) => {

              let clazz = x === 0 ? "gallery-column-first" : "gallery-column";

              let idx = (y * ncols) + x;

              console.log(idx);

              if (idx <= this.state.movies.length - 1) {
                  let movie: Video = this.state.movies[idx];
                  let m: string = movie.thumbnail;
                  let link: string = "/files/videos/" + movie.id;
                  return(
                      <td className="gallery-column">
                        <Thumbnail src={m} link={link} title={movie.title} />
                      </td>);
              }
              else {
                   return <td className={clazz}></td>;
              }

          });
          return <tr className="gallery-row full-width"> { cols } </tr>;
      });

      return (
        <div className="full-width">
           <table className="gallery">
             { rows }
           </table>
           <Pagination>
             <Pagination.First />
             <Pagination.Prev />
             <Pagination.Item active>{1}</Pagination.Item>
             <Pagination.Item>{2}</Pagination.Item>
             <Pagination.Ellipsis />
             <Pagination.Item>{10}</Pagination.Item>
             <Pagination.Next />
             <Pagination.Last />
           </Pagination>
        </div>
      );
  }
}

export default Gallery;
