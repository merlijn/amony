import React, {Component, useRef} from 'react';
import Container from 'react-bootstrap/Container';
import Navbar from 'react-bootstrap/Navbar';
import NavDropdown from 'react-bootstrap/NavDropdown';
import Nav from 'react-bootstrap/Nav';
import Form from 'react-bootstrap/Form';
import FormControl from 'react-bootstrap/FormControl';
import Button from 'react-bootstrap/Button';
import Gallery from './components/Gallery';

class State {
  constructor(
    public queryForm?: string,
    public searchQuery?: string
  ) { }
}

class App extends Component<{ }, State> {

  constructor(props: { }) {
    super(props);

    this.state = { };
  }

  searchChanged = (e: React.ChangeEvent<HTMLInputElement>) => {
    console.log("App.searchChanged()");

    this.state = {queryForm: e.target.value};
  };

  doSearch = (e: any) => {

    e.preventDefault();

    console.log("App.doSearch()");

    this.setState({
      searchQuery: this.state.queryForm
    })

    console.log(`${this.state.searchQuery}`);
  };

  render = () => {

    console.log("App.render()");

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
            <Form onSubmit={this.doSearch} inline>
              <FormControl type="text" placeholder="Search" onChange={this.searchChanged} className="mr-sm-2"/>
              <Button variant="outline-success" onClick={this.doSearch}>Search</Button>
            </Form>
          </Navbar.Collapse>
        </Navbar>

        <Gallery query={this.state.searchQuery} />

      </Container>
    );
  };
}

export default App;
