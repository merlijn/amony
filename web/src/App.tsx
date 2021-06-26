import React, {Component, useRef, useState} from 'react';
import Container from 'react-bootstrap/Container';
import Navbar from 'react-bootstrap/Navbar';
import NavDropdown from 'react-bootstrap/NavDropdown';
import Nav from 'react-bootstrap/Nav';
import Form from 'react-bootstrap/Form';
import FormControl from 'react-bootstrap/FormControl';
import Button from 'react-bootstrap/Button';
import Gallery from './components/Gallery';
import Player from './components/Player';
import { Route,  BrowserRouter, useParams, Switch, useHistory } from 'react-router-dom';


function App() {

  return (
    <Container className="root" fluid>

      <BrowserRouter>

        <TopBar />

        <div>
          <Switch>
            <Route exact path="/" component={ Gallery }  />
            <Route path="/search"  component= { Gallery } />
            <Route path="/video/:id" children={ <VideoRender /> } />
          </Switch>
        </div>

      </BrowserRouter>

    </Container>
  );
}

function TopBar() {

  const [query, setQuery] = useState("")

  const history = useHistory();

  const doSearch = (e: any) => {
    e.preventDefault();
    const target = "/search?q=" + query
    console.log("redirecting to: " + target)
    history.push(target);
  };

  const searchChanged = (e: React.ChangeEvent<HTMLInputElement>) => {
    console.log("App.searchChanged()");
    setQuery(e.target.value);
  };

  return(
    <Navbar bg="light" expand="sm">
      <Navbar.Toggle aria-controls="basic-navbar-nav"/>
      <Navbar.Collapse id="basic-navbar-nav">
        <Nav className="mr-auto">
          <Nav.Link href="/">Home</Nav.Link>
          <NavDropdown title="Lists" id="basic-nav-dropdown">
            <NavDropdown.Item href="#action/3.1">1</NavDropdown.Item>
            <NavDropdown.Item href="#action/3.2">2</NavDropdown.Item>
            <NavDropdown.Item href="#action/3.3">3</NavDropdown.Item>
            <NavDropdown.Item href="#action/3.4">4</NavDropdown.Item>
          </NavDropdown>
        </Nav>
        <Form onSubmit={doSearch} inline>
          <FormControl type="text" placeholder="Search" onChange={searchChanged} className="mr-sm-2"/>
          <Button variant="outline-success" onClick={doSearch}>Search</Button>
        </Form>
      </Navbar.Collapse>
    </Navbar>
  );
}

function VideoRender() {
  // We can use the `useParams` hook here to access
  // the dynamic pieces of the URL.
  let { id } = useParams<{ id: string }>();

  return (
    <Player videoId={id} />
  );
}

export default App;
