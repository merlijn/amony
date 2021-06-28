import React, {useEffect, useState} from "react";
import {useHistory} from "react-router-dom";
import {buildUrl} from "../api/Util";
import Navbar from "react-bootstrap/Navbar";
import Nav from "react-bootstrap/Nav";
import NavDropdown from "react-bootstrap/NavDropdown";
import Form from "react-bootstrap/Form";
import FormControl from "react-bootstrap/FormControl";
import Button from "react-bootstrap/Button";
import './TopNavBar.scss';

function TopNavBar() {

  const [query, setQuery] = useState("")
  const [collections, setCollections] = useState(["1", "2", "3"])

  const history = useHistory();

  const doSearch = (e: any) => {
    e.preventDefault();
    const target = buildUrl("/search", new Map( [["q", query]] ))
    history.push(target);
  };

  useEffect(() => {
    const target = buildUrl("/api/videos/collections", new Map())
  });

  const searchChanged = (e: React.ChangeEvent<HTMLInputElement>) => {
    console.log("App.searchChanged()");
    setQuery(e.target.value);
  };

  // fixed="top"

  return(
    <Navbar className="TopNavBar" expand="sm">
      <Navbar.Toggle aria-controls="basic-navbar-nav"/>
      <Navbar.Collapse id="basic-navbar-nav">
        <Nav className="mr-auto">
          <NavDropdown title="Lists" id="basic-nav-dropdown">
            {
              collections.map((c) => {
                return <NavDropdown.Item href="/collection/">{c}</NavDropdown.Item>
              })
            }
          </NavDropdown>
          <Nav.Link href="/">Home</Nav.Link>
        </Nav>
        <Form onSubmit={doSearch} inline>
          <FormControl type="text" placeholder="Search" onChange={searchChanged} className="mr-sm-2"/>
          <Button variant="outline-success" onClick={doSearch}>Search</Button>
        </Form>
      </Navbar.Collapse>
    </Navbar>
  );
}

export default TopNavBar