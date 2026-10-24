describe('release recovery',()=>{
  beforeEach(()=>{vi.resetModules();sessionStorage.clear()});
  afterEach(()=>vi.unstubAllGlobals());

  it('coalesces checks, validates the build and only reloads on one user action',async()=>{
    const revision='a'.repeat(40);
    const fetchMock=vi.fn().mockResolvedValue({ok:true,json:()=>Promise.resolve({component:'frontend',revision,builtAt:'2026-10-23T00:00:00Z'})});
    vi.stubGlobal('fetch',fetchMock);
    const recovery=await import('./build-recovery');
    const [first,second]=await Promise.all([recovery.checkBuildVersion(),recovery.checkBuildVersion()]);
    expect(first?.revision).toBe(revision);expect(second).toEqual(first);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const reload=vi.fn();expect(reload).not.toHaveBeenCalled();
    expect(recovery.recoverBuild(revision,reload)).toBe(true);
    expect(recovery.recoverBuild(revision,reload)).toBe(false);
    expect(reload).toHaveBeenCalledTimes(1);
  });
  it('ignores an invalid build response without reloading',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:true,json:()=>Promise.resolve({component:'backend',revision:'bad'})}));
    const recovery=await import('./build-recovery');
    await expect(recovery.checkBuildVersion()).resolves.toBeNull();
    expect(recovery.isChunkError(new Error('render failed'))).toBe(false);
    expect(recovery.isChunkError(new Error('Failed to fetch dynamically imported module'))).toBe(true);
  });
});
