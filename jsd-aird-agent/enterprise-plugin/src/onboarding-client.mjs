/** Enterprise users enter the workspace without the upstream preview announcement. */
export function registerEnterpriseOnboarding(scope, {React}) {
  function SkipPreviewNotice({complete}) {
    const finished = React.useRef(false);
    React.useEffect(() => {
      if (finished.current) return;
      finished.current = true;
      complete();
    }, [complete]);
    return null;
  }
  scope.slots.inject('settings.onboarding', () => scope.slots.register({
    name:'settings.onboarding', id:'welcome-notice', priority:-1000, order:-100,
  }, SkipPreviewNotice));
}
