import React, {Component} from 'react';
import Container from 'react-bootstrap/Container';
import Navbar from 'react-bootstrap/Navbar';
import NavDropdown from 'react-bootstrap/NavDropdown';
import Nav from 'react-bootstrap/Nav';
import Form from 'react-bootstrap/Form';
import FormControl from 'react-bootstrap/FormControl';
import Button from 'react-bootstrap/Button';
import Gallery from './components/Gallery';


class App extends Component {

  constructor(props: any) {
    super(props);
  }

  helloWorld = () => {

    console.log("Hello world!");
  };

  render = () => {

    return (
      <Container className="root" fluid>

        <Navbar bg="light" expand="sm">
          <Navbar.Toggle aria-controls="basic-navbar-nav"/>
          <Navbar.Collapse id="basic-navbar-nav">
            <Nav className="mr-auto">
              <Nav.Link href="#home">Home</Nav.Link>
              <NavDropdown title="Lists" id="basic-nav-dropdown">
                <NavDropdown.Item href="#action/3.1">1</NavDropdown.Item>
                <NavDropdown.Item href="#action/3.2">2</NavDropdown.Item>
                <NavDropdown.Item href="#action/3.3">3</NavDropdown.Item>
                <NavDropdown.Item href="#action/3.4">4</NavDropdown.Item>
              </NavDropdown>
            </Nav>
            <Form inline>
              <FormControl type="text" placeholder="Search" className="mr-sm-2"/>
              <Button variant="outline-success" onClick={this.helloWorld}>Search</Button>
            </Form>
          </Navbar.Collapse>
        </Navbar>

        <Gallery/>

      </Container>
    );
  };
}

export default App;
