import React, {Component} from 'react';
import {connect} from 'react-redux';
import Radium from 'radium';
import {replace} from 'react-router-redux';
import {Navbar, Nav, NavItem, MenuItem, NavDropdown} from 'react-bootstrap';

import ErrorBar from './components/ErrorBar';
import {
  receivedControllerState,
  errorGettingControllerState
} from './actions/actions';
import {sendGetStateRequest} from './api/api';

import {push} from 'react-router-redux';
import ControlPage from './components/control/ControlPage';

const PADDING = 16;
let styles = {
  base: {
    height: '100%',
    minHeight: 400,
    display: 'flex',
    flexDirection: 'column',
    justifyContent: 'stretch'
  },
  topBar: {
    background: 'rgba(0, 100, 0, 0.7)'
  },
  topBarTitle: {
    paddingLeft: PADDING,
    paddingTop: PADDING,
    paddingRight: PADDING,
    color: 'white',
    fontSize: 40
  },
  linkBar: {
    display: 'flex',
    flexDirection: 'dataRow',
    height: 32
  },
  linkButton: {
    display: 'flex',
    flexDirection: 'column',
    justifyContent: 'center',
    paddingLeft: PADDING,
    paddingRight: PADDING,
    ':hover': {
      background: 'rgba(255, 255, 255, 0.1)'
    }
  },
  link: {
    color: 'white'
  },
  error: {
    color: 'red'
  }
};

@Radium
class AppView extends Component {
  constructor(props) {
    super(props);
  }

  componentDidMount() {
    this.timer = setInterval(this.tick, 50);
  }

  componentWillUnmount() {
    clearInterval(this.timer);
  }

  tick = () => {
    sendGetStateRequest()
    .then(json => {
      this.props.handleControllerStateUpdate(json);
    })
    .catch(error => {
      console.error(error);
      this.props.handleErrorGettingControllerState(error);
    });
  };

  render() {
    return (<div style={[styles.base]}>
      <Navbar inverse collapseOnSelect>
        <Navbar.Header>
          <Navbar.Brand>
            <a href="#">multikinect</a>
          </Navbar.Brand>
          <Navbar.Toggle />
        </Navbar.Header>
      </Navbar>
      <ErrorBar errors={this.props.error.toArray()}/>

      {/*This will be replaced by react router */}
      {this.props.children && React.cloneElement(this.props.children, {})}
    </div>);
  }
}

AppView.contextTypes = {
  router: React.PropTypes.object
};

const mapStateToProps = (state, ownProps) => {
  return {
    controllerState: state.controllerState,
    error: state.error
  };
};

const mapDispatchToProps = (dispatch, ownProps) => {
  return {
    handleControllerStateUpdate: (state) => {
      dispatch(receivedControllerState(state));
    },
    handleErrorGettingControllerState: (error) => {
      dispatch(errorGettingControllerState(error));
    },
    handleControlLink: (event) => {
      dispatch(push(ControlPage.url()));
    }
  };
};

const App = connect(mapStateToProps, mapDispatchToProps)(AppView);

export default App;
